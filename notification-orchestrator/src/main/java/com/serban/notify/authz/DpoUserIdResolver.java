package com.serban.notify.authz;

import com.serban.notify.config.NotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the OpenFGA {@code user:<numeric-id>} principal identity from
 * the current JWT for Faz 23.2.B PR-K6 tenant-scoped DPO authz checks
 * (Codex thread {@code 019e59ea} iter-2 mandatory fix).
 *
 * <h3>Why a dedicated resolver</h3>
 *
 * <p>The notification-orchestrator security stack uses
 * {@code converter.setPrincipalClaimName("sub")} (see
 * {@code SecurityConfig.notifyJwtAuthenticationConverter}) — so
 * {@link Jwt#getSubject()} on an authenticated principal is the Keycloak
 * UUID. OpenFGA tuples for the DPO model are written with the canonical
 * numeric DB user id (matches the historical contract documented in
 * {@code permission-service.AuthorizationControllerV1} where
 * {@code /authz/check} resolves the numeric user id from the JWT
 * {@code userId}/{@code uid} claim, NOT {@code sub}).
 *
 * <p>Using {@code jwt.getSubject()} directly as the OpenFGA principal id
 * would produce {@code user:<uuid>} tuples — a different namespace from
 * what operators seed via the permission-service tuple writer. Every
 * DPO check would silently 403 even when the tuple is correct.
 *
 * <h3>Contract</h3>
 *
 * <p>Reads the JWT claim chain configured by
 * {@code notify.kvkk.dpo-user-id-claims} (default {@code [userId, uid]}).
 * The bounded allowlist enforced by {@code NotifyConfig.KvkkConfig}
 * pattern guard prevents operator misconfiguration ({@code sub} fallback
 * is intentionally NOT permitted).
 *
 * <p>Returns the first non-blank string claim value found, or
 * {@code null} when none of the configured claims are present. Slice
 * tests with no {@code Authentication} in the context get {@code null}
 * too — the same mirror-pattern as {@code NotifyOrgAccessGuard} and
 * {@code SubscriberIdentityGuard}.
 *
 * <p>{@link DpoAuthzService} treats {@code null} as fail-closed when
 * the K6 flag is enabled, so the silent-pass branch only kicks in when
 * the flag is off.
 */
@Component
public class DpoUserIdResolver {

    private static final Logger log = LoggerFactory.getLogger(DpoUserIdResolver.class);

    private final List<String> claimNames;

    public DpoUserIdResolver(NotifyConfig notifyConfig) {
        // Defensive copy is unnecessary — Spring-bound list is unmodifiable
        // and we only iterate it.
        this.claimNames = notifyConfig.kvkk().dpoUserIdClaims();
    }

    /**
     * Resolve the OpenFGA {@code user:<id>} principal id from the current
     * {@link SecurityContextHolder} JWT.
     *
     * @return the first non-blank string claim value from the configured
     *         claim chain; {@code null} if no Authentication present, no
     *         JWT token, or none of the configured claims match.
     */
    public String resolveOrNull() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        Authentication auth = ctx.getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return null;
        }
        Jwt jwt = jwtAuth.getToken();
        for (String claimName : claimNames) {
            Object value = jwt.getClaim(claimName);
            if (value == null) continue;
            String stringValue = String.valueOf(value).trim();
            if (!stringValue.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("DPO user id resolved from claim '{}' (length={})",
                        claimName, stringValue.length());
                }
                return stringValue;
            }
        }
        return null;
    }
}
