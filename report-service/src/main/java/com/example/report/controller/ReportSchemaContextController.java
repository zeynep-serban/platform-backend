package com.example.report.controller;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthResult;
import com.example.report.schema.SchemaTruthService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 Program 8e — Schema context endpoint for frontend useReportSchemaContext hook.
 *
 * <p>Spec §2.5: {@code GET /api/v1/reports/{key}/schema-context} → AG Grid
 * colDef enrichment için **visible report-column types** + {@code X-Schema-Truth-Tier}
 * response header (canonical tier signal).
 *
 * <p>Codex iter-1 absorb (2 BLOCKING):
 * <ul>
 *   <li>§1 — Authorization: {@code ReportController.metadata} pattern'iyle aynı —
 *       JWT + {@code resolveAndCheckAccess} + {@code ColumnFilter.getVisibleColumnDefinitions}
 *       zinciri. Yetkisiz kullanıcı 403; restricted column response'ta yok.</li>
 *   <li>§2 — Visible column shaping: snapshot path raw DB table.columns() değil,
 *       intersection (visible report column field name) ile filtrelenir; sourceQuery
 *       alias kolonları registry types fallback'ten alınır.</li>
 * </ul>
 *
 * <p>Tier provenance: {@link SchemaTruthService#fetchSnapshotWithTier} ile facade'da
 * orchestration; controller'da re-implementation YOK (8d metrics/MDC korunur).
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportSchemaContextController {

    private static final Logger log = LoggerFactory.getLogger(ReportSchemaContextController.class);
    public static final String TIER_HEADER = "X-Schema-Truth-Tier";

    private final ReportRegistry reportRegistry;
    private final SchemaTruthService schemaTruthService;
    private final PermissionResolver permissionClient;
    private final ReportAccessEvaluator accessEvaluator;
    private final ColumnFilter columnFilter;
    private final ReportAuditClient auditClient;

    public ReportSchemaContextController(ReportRegistry reportRegistry,
                                           SchemaTruthService schemaTruthService,
                                           PermissionResolver permissionClient,
                                           ReportAccessEvaluator accessEvaluator,
                                           ColumnFilter columnFilter,
                                           ReportAuditClient auditClient) {
        this.reportRegistry = reportRegistry;
        this.schemaTruthService = schemaTruthService;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.columnFilter = columnFilter;
        this.auditClient = auditClient;
    }

    @GetMapping("/{reportKey}/schema-context")
    public ResponseEntity<SchemaContextResponse> getSchemaContext(@PathVariable String reportKey,
                                                                    @AuthenticationPrincipal Jwt jwt) {
        Optional<ReportDefinition> defOpt = reportRegistry.get(reportKey);
        if (defOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ReportDefinition def = defOpt.get();

        // Codex iter-1 §1 absorb: report-level access check + column visibility filter
        // (mevcut ReportController.metadata pattern parity).
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);
        List<ColumnDefinition> visibleCols = columnFilter.getVisibleColumnDefinitions(def, authz);

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                reportKey, def.schemaMode(),
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE,
                "schema_context_endpoint");

        // Codex iter-1 §3 absorb: facade-owned tier orchestration; controller bypass YOK.
        SchemaTruthResult tierResult = schemaTruthService.fetchSnapshotWithTier(ctx, def.sourceSchema());

        // Codex iter-1 §2 absorb: visible report-column shape, NOT raw DB table.columns().
        Map<String, String> columnTypes = extractVisibleColumnTypes(
                tierResult.snapshot(), def, visibleCols);

        SchemaContextResponse body = new SchemaContextResponse(
                reportKey, tierResult.tier(), columnTypes);

        return ResponseEntity.ok()
                .header(TIER_HEADER, tierResult.tier())
                .body(body);
    }

    /**
     * Snapshot DB types (Tier 1/2) override registry types (Tier 3) per visible
     * column. Visible kolon snapshot'ta yoksa registry type kullanılır;
     * snapshot'taki extra (yetkisiz/raw DB) kolonlar response'a girmez.
     */
    private Map<String, String> extractVisibleColumnTypes(Optional<SchemaSnapshot> snapshotOpt,
                                                            ReportDefinition def,
                                                            List<ColumnDefinition> visibleCols) {
        // 1. Build DB-level types map from snapshot (if present).
        Map<String, String> dbTypes = Collections.emptyMap();
        if (snapshotOpt.isPresent() && def.source() != null) {
            SchemaSnapshot.TableInfo table = snapshotOpt.get().tables().get(def.source());
            if (table != null) {
                Map<String, String> tmp = new LinkedHashMap<>();
                for (SchemaSnapshot.ColumnInfo col : table.columns()) {
                    if (col.name() != null && col.dataType() != null) {
                        tmp.put(col.name(), col.dataType());
                    }
                }
                dbTypes = tmp;
            }
        }
        // 2. For each visible column: prefer DB type, fall back to registry type.
        Map<String, String> result = new LinkedHashMap<>();
        for (ColumnDefinition col : visibleCols) {
            if (col.field() == null || col.field().isBlank()) {
                continue;
            }
            String type = dbTypes.getOrDefault(col.field(), col.type());
            if (type != null && !type.isBlank()) {
                result.put(col.field(), type);
            }
        }
        return result;
    }

    private AuthzMeResponse resolveAndCheckAccess(ReportDefinition def, Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult result = accessEvaluator.evaluate(def, authz);

        if (result != ReportAccessEvaluator.AccessResult.ALLOWED) {
            auditClient.logReportAccessDenied(def.key(),
                    authz != null ? authz.getUserId() : "unknown",
                    extractEmail(jwt),
                    result.name());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, result.name());
        }
        return authz;
    }

    private String extractEmail(Jwt jwt) {
        if (jwt == null) return null;
        Object email = jwt.getClaims().get("email");
        return email != null ? email.toString() : null;
    }

    /**
     * Schema context response shape — frontend useReportSchemaContext consumer.
     *
     * @param reportKey   Report registry key (echo from path)
     * @param tier        "schema_service" | "committed_snapshot" | "registry_type" | "miss"
     * @param columnTypes Visible report column field → type ({@code DECIMAL(18,2)},
     *                    {@code number}, vb.); empty if all tiers missed
     */
    public record SchemaContextResponse(
            String reportKey,
            String tier,
            Map<String, String> columnTypes
    ) {
        public SchemaContextResponse {
            if (columnTypes == null) {
                columnTypes = Collections.emptyMap();
            }
        }
    }
}
