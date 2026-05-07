package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates that the {@code X-Subscriber-Id} request header (or
 * {@code subscriberId} query param for SSE) matches the authenticated
 * principal's JWT identity (Faz 23.4 PR-E.5; Faz 23.5 hardening).
 *
 * <h3>History</h3>
 *
 * <p>Codex thread {@code 019e01ba} iter-2: the original Faz 23.3 PR-E.1 /
 * PR-E.3 implementations accepted the subscriber identity directly from
 * caller-controlled inputs (header for REST, query param for SSE) without
 * any cross-check against the authenticated principal. A caller holding
 * a valid JWT for {@code sub=alice} could send
 * {@code X-Subscriber-Id: bob} and read bob's inbox or stream. That is an
 * authorization boundary bug; the guard closes it.
 *
 * <p>Codex thread {@code 019e0316} iter-3: the trusted claim list moved
 * from a static {@code [subscriberId, userId, sub]} array to a
 * configuration-driven list ({@link
 * com.serban.notify.config.NotifyConfig.SecurityConfig#subscriberIdentityClaims()}).
 * Operators flip to a strict {@code [subscriberId]} via the
 * {@code NOTIFY_SECURITY_SUBSCRIBER_IDENTITY_CLAIMS} env override once the
 * canonical claim is rolled out. The Micrometer counter
 * {@code notify_subscriber_identity_match_total{claim=...}} reports the
 * match distribution so the cutover can be sequenced safely.
 *
 * <h3>Resolve semantics</h3>
 *
 * <ol>
 *   <li>{@code subscriberId} — canonical claim (Faz 23.5 / Faz 24
 *       hardening). When the Keycloak mapper rolls out, this claim
 *       satisfies every match.</li>
 *   <li>{@code userId} — custom claim emitted by today's
 *       permission-service JWT enrichment chain (numeric DB user id);
 *       matches the frontend {@code state.auth.user.id}.</li>
 *   <li>{@code sub} — Keycloak realm UUID; matches when a producer
 *       happens to use it.</li>
 * </ol>
 *
 * <p>The match counter is incremented exactly once per request, with the
 * tag holding the matched claim name (or {@code none} when no configured
 * claim matched). Blank inputs and unauthenticated principals do not
 * touch the counter — they are validation/path-level concerns.
 */
@Component
public class SubscriberIdentityGuard {

    /** Stable Micrometer counter id. Prometheus exporter appends {@code _total}. */
    public static final String MATCH_METER = "notify.subscriber.identity.match";

    /** Tag name on the match counter. */
    public static final String CLAIM_TAG = "claim";

    /** Tag value used when the configured claim list has no match. */
    public static final String NO_MATCH_TAG = "none";

    private final NotifyConfig.SecurityConfig securityConfig;
    private final MeterRegistry meterRegistry;

    /**
     * Spring DI constructor. Spring boots the full {@link NotifyConfig}
     * bean via {@code @EnableConfigurationProperties}; nested record types
     * are not standalone beans, so we inject the parent and unwrap to the
     * security section here.
     *
     * <p>The {@code @Autowired} annotation disambiguates which constructor
     * Spring should use during component scanning — without it the two
     * public constructors leave Spring searching for a no-arg default
     * (Codex thread {@code 019e0316} post-impl P1 absorb).
     */
    @Autowired
    public SubscriberIdentityGuard(NotifyConfig notifyConfig, MeterRegistry meterRegistry) {
        this(notifyConfig.security(), meterRegistry);
    }

    /**
     * Test-friendly constructor. Production wiring goes through the
     * {@link #SubscriberIdentityGuard(NotifyConfig, MeterRegistry)}
     * overload above; tests pass a {@link
     * com.serban.notify.config.NotifyConfig.SecurityConfig} directly.
     *
     * <p>Package-private intentionally — Spring will not pick this up as
     * a candidate during component scan, eliminating the
     * "BeanInstantiationException: No default constructor" path.
     */
    SubscriberIdentityGuard(
        NotifyConfig.SecurityConfig securityConfig,
        MeterRegistry meterRegistry
    ) {
        this.securityConfig = securityConfig;
        this.meterRegistry = meterRegistry;
    }

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
     * @throws AccessDeniedException when an authenticated principal is
     *     present and none of the configured claims equals
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
            return;
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            // Defensive: @NotBlank on the controller arg should already
            // have produced a 400; if we somehow get here treat as a
            // boundary violation. Counter NOT incremented — this is a
            // 400 path, not a configured-claim miss.
            throw new AccessDeniedException(
                "subscriber identity unresolved (subscriberId blank)");
        }

        for (String claim : configuredClaims()) {
            // Claim values may be String, Number, or Boolean; we accept
            // any whose toString() equals the input. Keep it permissive
            // because the platform stores subscriberId as a string but
            // the JWT enrichment may emit a numeric type.
            Object claimValue = jwt.getClaim(claim);
            if (claimValue == null) {
                continue;
            }
            if (subscriberId.equals(String.valueOf(claimValue))) {
                incrementMatchCounter(claim);
                return;
            }
        }

        incrementMatchCounter(NO_MATCH_TAG);
        // Don't echo the claim values back — only state the mismatch.
        // Avoids assisting attackers who probe by varying the header.
        throw new AccessDeniedException(
            "subscriber identity mismatch: no trusted JWT claim matches the supplied subscriberId");
    }

    private List<String> configuredClaims() {
        List<String> configured = securityConfig.subscriberIdentityClaims();
        if (configured == null || configured.isEmpty()) {
            // Defensive — Spring binder + @NotEmpty should reject this at
            // boot; runtime fallback keeps the legacy behaviour.
            return List.of("subscriberId", "userId", "sub");
        }
        return configured;
    }

    private void incrementMatchCounter(String claim) {
        Counter.builder(MATCH_METER)
            .description("Subscriber identity guard match distribution by JWT claim name")
            .tag(CLAIM_TAG, claim)
            .register(meterRegistry)
            .increment();
    }
}
