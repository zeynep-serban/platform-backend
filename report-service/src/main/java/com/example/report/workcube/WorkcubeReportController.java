package com.example.report.workcube;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workcube MSSQL read-only HTTP endpoints (ADR-0005 Dual DataSource Reporting).
 *
 * <p>Path: {@code /api/v1/workcube/*}
 *
 * <p>Aktivasyon: {@code report.mssql.enabled=true} feature flag → {@code workcubeMssqlDataSource}
 * bean'i instantiate olduğunda bu controller da auto-register olur.
 *
 * <p>Endpoint'ler:
 * <ul>
 *   <li>GET /views — allowlist'teki view'leri listele (UI tooling)</li>
 *   <li>GET /views/{key} — allowlist key ile parametric query (filters + limit)</li>
 *   <li>GET /views/{key}/count — row count probe</li>
 * </ul>
 *
 * <p>Degraded mode (ADR-0005): MSSQL unreachable → 503 Service Unavailable
 * (PG endpoint'leri etkilenmez, ayrı health indicator).
 *
 * <p>Auth: Interim admin-only via {@link WorkcubeAccessGuard#isInterimAdmin(org.springframework.security.core.Authentication)}
 * (plan §7 Adım 1.5, Codex thread {@code 019e258f} iter-4 A-prime).
 * Non-admin authenticated user → 403 FORBIDDEN; no-auth → 401 (Spring Security chain).
 * TODO(Adım-11): replaced by WorkcubeQueryAdapter full authz/RLS/allowlist gate.
 */
@RestController
@RequestMapping("/api/v1/workcube")
@ConditionalOnBean(name = "workcubeMssqlDataSource")
@PreAuthorize("@workcubeAccessGuard.isInterimAdmin(authentication)")
public class WorkcubeReportController {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeReportController.class);

    private final WorkcubeReportRepository repo;

    public WorkcubeReportController(WorkcubeReportRepository repo) {
        this.repo = repo;
    }

    /**
     * Allowlist'teki view'leri listele.
     *
     * <p>Response shape:
     * <pre>
     * [
     *   { "key": "vw_company_summary", "view": "dbo.vw_company_summary", "columns": [...] },
     *   ...
     * ]
     * </pre>
     */
    @GetMapping(value = "/views", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listViews() {
        try {
            return ResponseEntity.ok(repo.listAllowedViews());
        } catch (DataAccessResourceFailureException ex) {
            return degraded("listViews", ex);
        }
    }

    /**
     * Allowlist key ile parametric query.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code limit}: 1..1000 (default 100)</li>
     *   <li>Diğer parametre: filters (allowed columns ile kısıtlanır)</li>
     * </ul>
     *
     * <p>Örnek: {@code GET /api/v1/workcube/views/vw_recent_orders?limit=50&status=APPROVED}
     */
    @GetMapping(value = "/views/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> queryView(
            @PathVariable("key") String key,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam Map<String, String> allParams) {
        if (!WorkcubeAllowlist.isAllowed(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "view_not_in_allowlist", "key", key));
        }
        // limit + path key dışındaki parametreler filter olarak değerlendirilecek
        Map<String, Object> filters = new HashMap<>();
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            if (!"limit".equals(e.getKey()) && !"key".equals(e.getKey())) {
                filters.put(e.getKey(), e.getValue());
            }
        }
        try {
            java.util.List<Map<String, Object>> rows = repo.queryView(key, filters, limit);
            return ResponseEntity.ok(Map.of(
                "key", key,
                "limit", limit,
                "rowCount", rows.size(),
                "rows", rows
            ));
        } catch (IllegalArgumentException ex) {
            log.debug("Workcube query bad request: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", ex.getMessage()));
        } catch (DataAccessResourceFailureException ex) {
            return degraded("queryView", ex);
        }
    }

    /**
     * Allowlist key ile row count.
     */
    @GetMapping(value = "/views/{key}/count", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> countView(@PathVariable("key") String key) {
        if (!WorkcubeAllowlist.isAllowed(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "view_not_in_allowlist", "key", key));
        }
        try {
            long count = repo.countRows(key);
            return ResponseEntity.ok(Map.of("key", key, "count", count));
        } catch (DataAccessResourceFailureException ex) {
            return degraded("countView", ex);
        }
    }

    /**
     * 503 Service Unavailable response — degraded mode.
     */
    private ResponseEntity<Map<String, Object>> degraded(String op, Exception ex) {
        log.warn("Workcube MSSQL degraded ({}): {}", op, ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "error", "mssql_unavailable",
                "op", op,
                "message", "Workcube MSSQL temporarily unreachable; PG-backed reports OK.",
                "retryAfterSec", 30
            ));
    }
}
