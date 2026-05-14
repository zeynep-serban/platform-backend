package com.example.report.query;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Phase 2 Program 2 — Pre-request tenant boundary preflight for report
 * execution endpoints (Adım 5).
 *
 * <p>Codex thread {@code 019e258f} iter-12/14 PARTIAL absorb. Replaces the
 * implicit "controller picks up scope narrow when it gets around to it"
 * pattern with an explicit preflight that runs before the handler is
 * invoked, on a narrow path matrix (data / query / export only). Catalog,
 * metadata, schema-context, alerts and schedules pass through untouched.
 *
 * <h2>Why not in the controller</h2>
 * Controllers already call {@link CompanyHeaderScopeNarrower#narrow} for
 * the downstream query path. That happens after request binding, after
 * exception advisors register, and is duplicated across every execution
 * endpoint. Hoisting the boundary decision into an interceptor centralizes
 * the failure mode (one 400/403 site) and lets a future PR consume
 * {@link #NARROWED_AUTHZ_ATTRIBUTE} instead of re-narrowing.
 *
 * <h2>Report-aware enforcement (Codex iter-14 revision)</h2>
 * The guard only enforces tenant selection for reports whose schemaMode
 * actually depends on a tenant axis ({@code yearly} per-year partitions
 * or {@code current} per-tenant master schema). Static / literal reports
 * fall through; they do not address a tenant-partitioned schema, and
 * forcing {@code X-Company-Id} on them would be a meaning-shift, not a
 * security tightening.
 *
 * <h2>What this guard does NOT do</h2>
 * <ul>
 *   <li>Replace controller-side narrow. Both paths run; semantically
 *       identical (narrow is idempotent on already-singleton COMPANY).
 *       Controller adoption of {@link #NARROWED_AUTHZ_ATTRIBUTE} is a
 *       follow-up PR.</li>
 *   <li>Cover Workcube direct endpoints ({@code /api/v1/workcube/views}).
 *       Those run under the Adım 1.5 interim super-admin gate and will
 *       be folded into the Adım 11 WorkcubeQueryAdapter composition.</li>
 *   <li>Set {@link com.example.commonauth.scope.ScopeContextHolder}. The
 *       common-auth filter chain already owns that ThreadLocal; the guard
 *       writes a request attribute only.</li>
 * </ul>
 */
public class TenantBoundaryGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantBoundaryGuard.class);

    /** Request attribute name carrying the narrowed authz for handler use. */
    public static final String NARROWED_AUTHZ_ATTRIBUTE =
            TenantBoundaryGuard.class.getName() + ".narrowedAuthz";

    private final PermissionResolver permissionResolver;
    private final CompanyHeaderScopeNarrower narrower;
    private final ReportRegistry reportRegistry;

    public TenantBoundaryGuard(PermissionResolver permissionResolver,
                               CompanyHeaderScopeNarrower narrower,
                               ReportRegistry reportRegistry) {
        this.permissionResolver = permissionResolver;
        this.narrower = narrower;
        this.reportRegistry = reportRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String reportKey = extractReportKey(request);
        if (reportKey == null) {
            return true;
        }

        Optional<ReportDefinition> defOpt = reportRegistry.get(reportKey);
        if (defOpt.isEmpty()) {
            return true;
        }
        ReportDefinition def = defOpt.get();

        if (!isTenantBound(def)) {
            log.debug("TenantBoundaryGuard bypass for non-tenant report '{}'", reportKey);
            return true;
        }

        Jwt jwt = extractJwt();
        if (jwt == null) {
            return true;
        }

        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        if (authz == null) {
            return true;
        }

        String header = request.getHeader(CompanyHeaderScopeNarrower.HEADER_NAME);
        AuthzMeResponse narrowed = narrower.narrow(authz, header);

        Set<String> companies = narrowed.getScopeRefIds("COMPANY");

        if (narrowed.isSuperAdmin() && companies.isEmpty()) {
            throw new TenantSelectionRequiredException(reportKey,
                    "Tenant-bound report '" + reportKey + "' requires an explicit X-Company-Id "
                            + "picker header for super-admin execution.");
        }
        if (!narrowed.isSuperAdmin() && companies.size() > 1) {
            throw new TenantSelectionRequiredException(reportKey,
                    "Tenant-bound report '" + reportKey + "' requires an X-Company-Id "
                            + "picker header for multi-company users; got " + companies.size()
                            + " allowed companies.");
        }
        if (!narrowed.isSuperAdmin() && companies.isEmpty()) {
            throw new TenantSelectionRequiredException(reportKey,
                    "Tenant-bound report '" + reportKey + "' requires a COMPANY scope; "
                            + "authz context has none.");
        }

        request.setAttribute(NARROWED_AUTHZ_ATTRIBUTE, narrowed);
        log.debug("TenantBoundaryGuard preflight pass for report '{}', companies={}", reportKey, companies);
        return true;
    }

    private boolean isTenantBound(ReportDefinition def) {
        return "yearly".equals(def.schemaMode()) || "current".equals(def.schemaMode());
    }

    @SuppressWarnings("unchecked")
    private String extractReportKey(HttpServletRequest request) {
        Map<String, String> vars = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars != null ? vars.get("key") : null;
    }

    private Jwt extractJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }
}
