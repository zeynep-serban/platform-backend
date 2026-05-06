package com.example.report.workcube;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.commonauth.scope.ScopeContext;

/**
 * Read-only catalog endpoint for the muavin / dynamic-report
 * CompanyPicker dropdown.
 *
 * <p>Path: {@code GET /api/v1/reports/company-options}
 *
 * <p>Path is intentionally report-owned (not the platform-wide
 * {@code /api/v1/companies}, which is gated to {@code core-data-service}
 * via the api-gateway route table). Per Codex 019dfb0b iter-1: keeping
 * Workcube catalog separate avoids confusion between the platform's
 * native company API and the per-tenant Workcube schema list.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Caller must be authenticated (Spring Security on /api/*).</li>
 *   <li>Super-admin → full catalog.</li>
 *   <li>Scoped user → filtered to {@link ScopeContext#allowedCompanyIds()}.
 *       Frontend gets exactly the same list it would get from a
 *       backend-side scope check, so the dropdown can't show entries the
 *       user couldn't load anyway.</li>
 * </ul>
 *
 * <p>Note that returning a filtered list is a UX guard, not a security
 * guard — every report metadata/data/export call still re-checks
 * {@code X-Company-Id} server-side.
 *
 * <p>Degraded mode (Codex 019dfb15 iter-2 absorb #3): when MSSQL is
 * unreachable, the underlying
 * {@link DataAccessResourceFailureException} propagates from the
 * repository through the cache layer; this controller converts it to
 * 503 to mirror {@link WorkcubeReportController#degraded}. Returning
 * 200 with an empty list would (a) silently mask the outage to the
 * frontend, which can't distinguish "no companies authorized" from
 * "MSSQL down", and (b) — pre-fix — risk poisoning the catalog cache
 * with an empty list for the full TTL. With propagation, the next
 * request retries.
 *
 * <p>Activation: tied directly to the {@code workcubeMssqlDataSource}
 * bean (Codex 019dfb15 iter-2 absorb #2 — chained {@code @ConditionalOnBean}
 * across component-scanned beans is brittle; this matches the pattern
 * used by {@link WorkcubeReportController}). When the feature flag
 * {@code report.mssql.enabled} is off, the entire path 404s and the
 * frontend falls back to the static {@code Şirket #1..43} list.
 */
@RestController
@RequestMapping("/api/v1/reports/company-options")
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class CompanyOptionsController {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsController.class);

    private final CompanyOptionsService service;

    public CompanyOptionsController(CompanyOptionsService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list() {
        ScopeContext scope = ScopeContext.current();
        if (scope == null) {
            // Spring Security on /api/* normally guarantees ScopeContext is
            // populated; this is a defensive 401 in case the filter chain
            // ordering ever drifts.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<CompanyOptionsRepository.CompanyOption> options = service.findAuthorized(scope);
            log.debug("CompanyOptionsController: returning {} options for user={} superAdmin={}",
                    options.size(), scope.userId(), scope.superAdmin());
            return ResponseEntity.ok(options);
        } catch (DataAccessResourceFailureException ex) {
            return degraded(ex);
        }
    }

    /**
     * 503 Service Unavailable — mirrors {@link WorkcubeReportController#degraded}
     * so the frontend can branch on a single MSSQL-down contract for every
     * Workcube endpoint.
     */
    private ResponseEntity<Map<String, Object>> degraded(Exception ex) {
        log.warn("CompanyOptions MSSQL degraded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "mssql_unavailable",
                        "op", "companyOptions",
                        "message", "Workcube MSSQL temporarily unreachable; PG-backed reports OK.",
                        "retryAfterSec", 30));
    }
}
