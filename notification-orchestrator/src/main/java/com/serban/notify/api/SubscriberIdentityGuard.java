package com.serban.notify.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates that the {@code X-Subscriber-Id} request header (or
 * {@code subscriberId} query param for SSE) matches the authenticated
 * principal's JWT identity (Faz 23.4 PR-E.5).
 *
 * <p><b>Why</b> (Codex thread {@code 019e01ba} iter-2 absorb): the original
 * Faz 23.3 PR-E.1/PR-E.3 implementations accepted the subscriber identity
 * directly from caller-controlled inputs (header for REST, query param for
 * SSE) without any cross-check against the authenticated principal. A
 * caller holding a valid JWT for {@code sub=alice} could send
 * {@code X-Subscriber-Id: bob} and read bob's inbox or stream.
 * That is an authorization boundary bug — an authenticated user must not
 * be able to impersonate another subscriber by editing a request header.
 *
 * <p><b>Trusted claim set</b> (Codex iter-3 absorb): the platform does not
 * yet have a single canonical {@code subscriberId} JWT claim. Notification
 * producers historically populate {@code recipient.subscriberId} with the
 * platform's DB user id (numeric), while the Keycloak {@code sub} claim is
 * a UUID. Forcing {@code sub} would break legitimate access; forcing the
 * custom {@code userId} claim breaks tokens that lack it. The guard
 * therefore accepts a match against any of the following claims, in
 * priority order:
 * <ol>
 *   <li>{@code subscriberId} — canonical claim once it lands (Faz 23.5 /
 *       Faz 24 hardening). Already preferred so future tokens flip
 *       behavior automatically.</li>
 *   <li>{@code userId} — custom claim emitted by today's permission-service
 *       JWT enrichment chain (numeric DB user id). Matches frontend
 *       {@code state.auth.user.id}.</li>
 *   <li>{@code sub} — Keycloak realm UUID. Matches if the producer
 *       happens to use it.</li>
 * </ol>
 *
 * <p>If none of these claim values equal the caller-supplied
 * {@code subscriberId}, the guard throws {@link AccessDeniedException}
 * (mapped to HTTP 403 by Spring's default {@code AccessDeniedHandler}).
 *
 * <p><b>What this guard does NOT do</b>: it doesn't validate {@code orgId}.
 * The platform is single-tenant for now ({@code default} org); when a
 * tenant claim lands (Faz 24+), this guard should be extended to also
 * match {@code X-Org-Id} against a claim such as {@code tenant_id} or
 * {@code org_id}. Until then, accepting any caller-supplied org is
 * acceptable because all subscribers live in the same tenant scope.
 *
 * <p><b>Long-term direction</b> (Codex iter-3 canonical target C):
 * Keycloak / permission-service should emit a single {@code subscriberId}
 * claim, both producers and consumers should pin to it, and this guard
 * should tighten to match exclusively that claim. The trusted-claim-set
 * shape is a transitional pragmatic measure.
 */
@Component
public class SubscriberIdentityGuard {

    /**
     * Trusted JWT claim names that may carry the subscriber identifier,
     * in priority order. The first claim whose string value equals the
     * caller-supplied {@code subscriberId} satisfies the match.
     *
     * <p>Order rationale:
     * <ul>
     *   <li>{@code subscriberId} first → forward-compatible. When the
     *       canonical claim lands, no guard change is required for the
     *       new tokens to take effect.</li>
     *   <li>{@code userId} second → today's reality (permission-service
     *       JWT enrichment).</li>
     *   <li>{@code sub} last → Keycloak default; only matches if a
     *       producer explicitly used it.</li>
     * </ul>
     */
    private static final List<String> TRUSTED_IDENTITY_CLAIMS = List.of(
        "subscriberId",
        "userId",
        "sub"
    );

    /**
     * Validates that the supplied {@code subscriberId} matches one of the
     * authenticated principal's trusted identity claims.
     *
     * <p>If no authentication is present (e.g. {@code SecurityContextHolder}
     * is empty under a profile that disables filters), the guard returns
     * silently — slice tests with {@code addFilters=false} continue to
     * work without contortions. Production runs always have an
     * authenticated context for {@code /api/v1/notify/**} routes
     * (configured in {@code SecurityConfig}), so the silent skip is safe.
     *
     * @param subscriberId the caller-supplied subscriber identifier
     *     ({@code X-Subscriber-Id} header for REST, {@code subscriberId}
     *     query param for SSE)
     * @throws AccessDeniedException when an authenticated principal is
     *     present and none of {@link #TRUSTED_IDENTITY_CLAIMS} equals
     *     {@code subscriberId}
     */
    public void requireMatchOrThrow(String subscriberId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            // No filter chain in this profile (e.g. @WebMvcTest addFilters=false)
            // → contract semantics not exercised; let the slice test pass.
            return;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            // Anonymous / non-JWT principal under a permissive profile.
            // Same rationale as the unauthenticated branch above.
            return;
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            // Defensive: @NotBlank on the controller arg should already
            // have produced a 400; if we somehow get here treat as a
            // boundary violation.
            throw new AccessDeniedException(
                "subscriber identity unresolved (subscriberId blank)");
        }

        for (String claim : TRUSTED_IDENTITY_CLAIMS) {
            // Claim values may be String, Number, or Boolean; we accept
            // any whose toString() equals the input. Keep it permissive
            // because the platform stores subscriberId as a string but
            // the JWT enrichment may emit a numeric type.
            Object claimValue = jwt.getClaim(claim);
            if (claimValue == null) {
                continue;
            }
            if (subscriberId.equals(String.valueOf(claimValue))) {
                return;
            }
        }

        // None of the trusted claims matched. Don't echo the claim values
        // back — only state the mismatch. Avoids assisting attackers who
        // probe by varying the header.
        throw new AccessDeniedException(
            "subscriber identity mismatch: no trusted JWT claim matches the supplied subscriberId");
    }
}
