package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Org-boundary guard for {@code /api/v1/notify/intents/&#123;intentId&#125;/deliveries}
 * and {@code /api/v1/admin/notify/deliveries} endpoints (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: header-trust for
 * org boundary is a leak vector — the caller's signed JWT must declare the
 * org it can address; the {@code X-Org-Id} request header is only the
 * <i>selector</i>, never the authority. This guard reconciles the two.
 *
 * <h3>Resolve order</h3>
 *
 * <p>The trusted org set comes from JWT claims, in priority order:
 * <ol>
 *   <li>{@code org_id} (single tenant principal)</li>
 *   <li>{@code tenant_id} (tenant-platform alias)</li>
 *   <li>{@code allowed_orgs} (string list — multi-tenant operator)</li>
 *   <li>{@code notify.security.default-org-id} configuration fallback —
 *       <b>only</b> when {@code requestedOrgId == defaultOrgId}, never as a
 *       wildcard</li>
 * </ol>
 *
 * <p>If the {@code SecurityContext} carries no authentication (slice-test
 * pattern, same as {@link SubscriberIdentityGuard}), the guard silently
 * passes — production filter chain enforces 401 upstream of this guard.
 *
 * <h3>Cross-org bypass (forward-compat)</h3>
 *
 * <p>The permission name {@code notify-deliveries-cross-org} is <b>not</b>
 * seeded in the v1 catalogue (Codex iter-3 AGREE: "v1 kapalı, ileri uyumlu").
 * However, if a future signed JWT carries this authority, this guard accepts
 * any {@code requestedOrgId}. v1 default behaviour: cross-org calls 403.
 */
@Component
public class NotifyOrgAccessGuard {

    private static final String CLAIM_ORG_ID = "org_id";
    private static final String CLAIM_TENANT_ID = "tenant_id";
    private static final String CLAIM_ALLOWED_ORGS = "allowed_orgs";
    private static final String AUTHORITY_CROSS_ORG = "notify-deliveries-cross-org";

    private final NotifyConfig.SecurityConfig securityConfig;

    public NotifyOrgAccessGuard(NotifyConfig notifyConfig) {
        this.securityConfig = notifyConfig.security();
    }

    /**
     * Verify that the authenticated principal can claim {@code requestedOrgId}.
     *
     * @throws AccessDeniedException when the JWT's trusted org set does not
     *         include {@code requestedOrgId} and no cross-org authority is
     *         present
     * @throws IllegalArgumentException when {@code requestedOrgId} is blank
     */
    public void requireOrgAccessOrThrow(String requestedOrgId) {
        if (requestedOrgId == null || requestedOrgId.isBlank()) {
            throw new IllegalArgumentException("X-Org-Id header is required");
        }

        SecurityContext ctx = SecurityContextHolder.getContext();
        Authentication auth = ctx.getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            // Slice/repository tests run without an Authentication — silent
            // pass mirrors SubscriberIdentityGuard. Production endpoints sit
            // behind the resource-server filter chain which would 401 first.
            return;
        }

        // Forward-compat cross-org bypass (v1: not seeded in catalogue)
        if (hasAuthority(auth, AUTHORITY_CROSS_ORG)) {
            return;
        }

        // JWT principal — resolve trusted org set
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (matchesClaim(jwt, CLAIM_ORG_ID, requestedOrgId)) return;
            if (matchesClaim(jwt, CLAIM_TENANT_ID, requestedOrgId)) return;
            if (matchesAllowedOrgs(jwt, requestedOrgId)) return;
        }

        // Default-org fallback ONLY when requested == default (NOT a wildcard)
        String defaultOrgId = securityConfig.defaultOrgId();
        if (defaultOrgId != null
            && !defaultOrgId.isBlank()
            && requestedOrgId.equals(defaultOrgId)) {
            return;
        }

        throw new AccessDeniedException(
            "Caller is not authorised for org " + requestedOrgId
        );
    }

    private boolean matchesClaim(Jwt jwt, String claimName, String requested) {
        Object value = jwt.getClaim(claimName);
        if (value == null) return false;
        return requested.equals(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private boolean matchesAllowedOrgs(Jwt jwt, String requested) {
        Object value = jwt.getClaim(CLAIM_ALLOWED_ORGS);
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element != null && requested.equals(String.valueOf(element))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        if (auth.getAuthorities() == null) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga != null && Objects.equals(authority, ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the configured default org id (may be empty/null in multi-tenant
     * setups). Exposed for tests / observability surfaces.
     */
    public Optional<String> getDefaultOrgId() {
        String defaultOrgId = securityConfig.defaultOrgId();
        if (defaultOrgId == null || defaultOrgId.isBlank()) return Optional.empty();
        return Optional.of(defaultOrgId.toLowerCase(Locale.ROOT));
    }
}
