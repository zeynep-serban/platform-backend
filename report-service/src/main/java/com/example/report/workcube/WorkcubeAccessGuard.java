package com.example.report.workcube;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Interim admin-only access guard for Workcube endpoints.
 *
 * <p><b>Deprecation (Adım 11.4):</b> The class-level {@code @PreAuthorize} on
 * {@link WorkcubeReportController} has been REMOVED. Full authz pipeline now
 * runs in {@link WorkcubeReportExecutionService} (ReportAccessEvaluator +
 * ColumnFilter + RowFilterInjector + ReportAuditClient). This guard is still
 * referenced only by legacy {@code /views/*} method-level {@code @PreAuthorize}
 * for back-compat until Adım 11.5 cutover, after which the legacy endpoints
 * and this class will be removed together.
 *
 * <p>Initially active 2026-05-14 — plan §7 Adım 1.5, Codex thread
 * {@code 019e258f} iter-4 (A-prime AGREE). Restricted
 * {@code /api/v1/workcube/*} to super-admin users only (OpenFGA
 * {@code admin@organization:default} tuple) as pre-prod compensating control.
 * Adım 11.4 (this PR) replaced that with the full service-level authz
 * pipeline for the new {@code /reports/*} endpoints.
 *
 * <p>Fail-closed semantics (returns {@code false} → triggers 403 FORBIDDEN
 * via Spring Method Security):
 * <ul>
 *   <li>{@code null} authentication (anonymous; chain normally returns 401 earlier)</li>
 *   <li>Non-JWT authentication (e.g. anonymous / service token without OpenFGA mapping)</li>
 *   <li>{@code null} AuthzMeResponse (PermissionResolver failure / cache miss)</li>
 *   <li>{@code superAdmin} flag {@code null} or {@code false}</li>
 * </ul>
 *
 * <p>Returns {@code true} only when {@link AuthzMeResponse#isSuperAdmin()} is
 * {@code true} — i.e. OpenFGA {@code admin@organization:default} check passed.
 *
 * @deprecated since Adım 11.4 — full authz pipeline in
 *     {@link WorkcubeReportExecutionService} supersedes this guard. Remove
 *     together with legacy {@code /views/*} endpoints at Adım 11.5 cutover.
 */
@Deprecated(forRemoval = true, since = "Adim-11.4")
@Component("workcubeAccessGuard")
public class WorkcubeAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeAccessGuard.class);

    private final PermissionResolver permissionResolver;

    public WorkcubeAccessGuard(PermissionResolver permissionResolver) {
        this.permissionResolver = permissionResolver;
    }

    /**
     * Method security expression target.
     *
     * <p>Usage:
     * <pre>{@code
     *   @PreAuthorize("@workcubeAccessGuard.isInterimAdmin(authentication)")
     * }</pre>
     */
    public boolean isInterimAdmin(Authentication authentication) {
        if (authentication == null) {
            log.debug("WorkcubeAccessGuard: null authentication → deny");
            return false;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            log.debug("WorkcubeAccessGuard: non-JWT authentication ({}) → deny",
                    authentication.getClass().getSimpleName());
            return false;
        }
        Jwt jwt = jwtAuth.getToken();
        AuthzMeResponse authz;
        try {
            authz = permissionResolver.getAuthzMe(jwt);
        } catch (RuntimeException ex) {
            log.warn("WorkcubeAccessGuard: PermissionResolver failure → deny (fail-closed): {}",
                    ex.getMessage());
            return false;
        }
        if (authz == null) {
            log.debug("WorkcubeAccessGuard: AuthzMeResponse null → deny");
            return false;
        }
        boolean superAdmin = authz.isSuperAdmin();
        if (!superAdmin) {
            log.debug("WorkcubeAccessGuard: user is not super-admin → deny");
        }
        return superAdmin;
    }
}
