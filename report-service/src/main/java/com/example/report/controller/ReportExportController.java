package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.ColumnVO;
import com.example.report.dto.ReportExportRequestDto;
import com.example.report.dto.ReportQueryErrorDto;
import com.example.report.dto.ReportQueryRequestDto;
import com.example.report.export.CsvStreamingExporter;
import com.example.report.export.ExcelStreamingExporter;
import com.example.report.export.ExportColumn;
import com.example.report.query.QueryEngine;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.PivotValue;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.security.JwtClaimExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportExportController {

    private static final Logger log = LoggerFactory.getLogger(ReportExportController.class);

    private final ReportRegistry registry;
    private final PermissionResolver permissionClient;
    private final ReportAccessEvaluator accessEvaluator;
    private final QueryEngine queryEngine;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportAuditClient auditClient;
    private final ObjectMapper objectMapper;
    private final CompanyHeaderScopeNarrower companyHeaderNarrower;

    public ReportExportController(ReportRegistry registry,
                                   PermissionResolver permissionClient,
                                   ReportAccessEvaluator accessEvaluator,
                                   QueryEngine queryEngine,
                                   NamedParameterJdbcTemplate jdbc,
                                   ReportAuditClient auditClient,
                                   ObjectMapper objectMapper,
                                   CompanyHeaderScopeNarrower companyHeaderNarrower) {
        this.registry = registry;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.queryEngine = queryEngine;
        this.jdbc = jdbc;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
        this.companyHeaderNarrower = companyHeaderNarrower;
    }

    @GetMapping("/{key}/export")
    public ResponseEntity<StreamingResponseBody> exportReport(
            @PathVariable String key,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String advancedFilter,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));

        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult accessResult = accessEvaluator.evaluate(def, authz);
        if (accessResult != ReportAccessEvaluator.AccessResult.ALLOWED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, accessResult.name());
        }
        if (!accessEvaluator.canExport(authz)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REPORT_EXPORT permission required");
        }
        // Narrow to the picker selection so exported data matches what
        // the user sees on screen (mirrors getData; without it the export
        // would silently include data from every allowed company).
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        Map<String, Object> agGridFilter = parseJson(advancedFilter, new TypeReference<>() {});
        List<Map<String, String>> sortModel = parseJson(sort, new TypeReference<>() {});

        SqlBuilder.BuiltQuery exportQuery = queryEngine.buildExportQuery(def, scopedAuthz, agGridFilter, sortModel);
        List<String> visibleColumns = queryEngine.getVisibleColumns(def, scopedAuthz);

        String userId = jwt != null ? JwtClaimExtractor.extractAuditUsername(jwt) : authz.getUserId();
        auditClient.logReportExport(key, authz.getUserId(), userId, format);

        // Codex 019e0c99 iter-3 §C: export path also propagates degradation
        // warnings as X-Report-Degraded header (dedupe by code).
        var degradationHeaders = com.example.report.query.DegradationHeaders.of(exportQuery.warnings());

        if ("excel".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            StreamingResponseBody body = out ->
                    ExcelStreamingExporter.export(jdbc, exportQuery, visibleColumns, def.title(), out);
            return ResponseEntity.ok()
                    .headers(degradationHeaders)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + key + ".xlsx\"")
                    .body(body);
        }

        StreamingResponseBody body = out ->
                CsvStreamingExporter.export(jdbc, exportQuery, visibleColumns, out);
        return ResponseEntity.ok()
                .headers(degradationHeaders)
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + key + ".csv\"")
                .body(body);
    }

    /**
     * PR-0.5b (Codex thread 019e2cd7, post-impl REVISE absorb): POST
     * /export. Accepts the AG Grid grid-state snapshot (rowGroupCols
     * + valueCols + pivotCols + pivotMode + filterModel + sortModel)
     * and dispatches to the appropriate {@code SqlBuilder}
     * export-query builder. Shares the validation/classifier
     * contracts with {@link ReportController}'s live {@code /query}
     * path so the exported view is identical to what the user sees
     * on screen — same fail-closed semantics, structured error
     * codes, and aggregation sanitisation.
     *
     * <p>Returns a {@link ResponseEntity} of {@code Object} so the
     * happy-path streams a binary body while error paths return
     * structured JSON {@link ReportQueryErrorDto}, matching the
     * live query path's error envelope.
     */
    @PostMapping("/{key}/export")
    public ResponseEntity<StreamingResponseBody> exportReportPost(
            @PathVariable String key,
            @RequestBody(required = false) ReportExportRequestDto requestBody,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));

        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult accessResult = accessEvaluator.evaluate(def, authz);
        if (accessResult != ReportAccessEvaluator.AccessResult.ALLOWED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, accessResult.name());
        }
        if (!accessEvaluator.canExport(authz)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REPORT_EXPORT permission required");
        }
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        ReportExportRequestDto safeRequest = requestBody != null ? requestBody
                : new ReportExportRequestDto(null, null, null, null, null, null, null);
        String format = safeRequest.format();
        if (format == null || format.isBlank()) {
            format = "csv";
        }

        Map<String, Object> filterModel = safeRequest.filterModel();
        List<Map<String, String>> sortModel = safeRequest.sortModel();

        // Resolve registry capability sets from visible columns —
        // same derivation the metadata endpoint uses so the export
        // contract stays in lockstep with the user-visible UI gates.
        List<ColumnDefinition> visibleColDefs = new ArrayList<>();
        Map<String, ColumnDefinition> columnDefByField = new HashMap<>();
        java.util.Set<String> visibleFieldSet = new java.util.HashSet<>(
                queryEngine.getVisibleColumns(def, scopedAuthz));
        for (ColumnDefinition cd : def.columns()) {
            if (visibleFieldSet.contains(cd.field())) {
                visibleColDefs.add(cd);
                columnDefByField.put(cd.field(), cd);
            }
        }
        java.util.Set<String> groupableFields = visibleColDefs.stream()
                .filter(ColumnDefinition::groupable)
                .map(ColumnDefinition::field)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> aggregatableFields = visibleColDefs.stream()
                .filter(ColumnDefinition::aggregatable)
                .map(ColumnDefinition::field)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> pivotableFields = visibleColDefs.stream()
                .filter(ColumnDefinition::pivotable)
                .map(ColumnDefinition::field)
                .collect(java.util.stream.Collectors.toSet());

        boolean pivotRequest = isPivotRequest(safeRequest, groupableFields, pivotableFields);
        boolean groupedRequest = !pivotRequest && isGroupedRequest(safeRequest, groupableFields);

        SqlBuilder.BuiltQuery exportQuery;
        List<ExportColumn> exportColumns;
        try {
            if (pivotRequest) {
                String groupCol = safeRequest.rowGroupCols().get(0).field();
                String pivotCol = safeRequest.pivotCols().get(0).field();
                ColumnDefinition pivotCd = columnDefByField.get(pivotCol);
                if (pivotCd == null || pivotCd.pivotValues() == null
                        || pivotCd.pivotValues().isEmpty()) {
                    return badRequest("PIVOT_NOT_CONFIGURED",
                            "Pivot export requires the pivot column to declare pivotValues "
                                    + "in the report registry, got: " + pivotCol);
                }
                List<PivotValue> pivotValues = pivotCd.pivotValues();
                List<SqlBuilder.GroupedAggregation> aggregations;
                try {
                    aggregations = ReportController.sanitizeAggregations(
                            safeRequest.valueCols(), aggregatableFields, visibleColDefs);
                } catch (IllegalArgumentException iae) {
                    return badRequest("INVALID_AGGREGATION_REQUEST", iae.getMessage());
                }
                // Budget check matches the live query path's
                // PIVOT_BUDGET_EXCEEDED code (Codex 019e2695).
                long totalOutputColumns =
                        (long) pivotValues.size() * aggregations.size();
                if (totalOutputColumns > SqlBuilder.MAX_PIVOT_OUTPUT_COLUMNS) {
                    return badRequest("PIVOT_BUDGET_EXCEEDED",
                            "Pivot output column budget exceeded: pivotValues("
                                    + pivotValues.size() + ") * valueCols("
                                    + aggregations.size() + ") = "
                                    + totalOutputColumns + " > "
                                    + SqlBuilder.MAX_PIVOT_OUTPUT_COLUMNS);
                }

                SqlBuilder.PivotedBuiltQuery pivotQuery =
                        queryEngine.buildPivotedGroupedExportQuery(
                                def, scopedAuthz, groupCol, pivotCol, pivotValues,
                                aggregations, filterModel, sortModel);
                exportQuery = new SqlBuilder.BuiltQuery(
                        pivotQuery.sql(), pivotQuery.params(), pivotQuery.warnings());
                exportColumns = pivotExportColumns(groupCol, columnDefByField, pivotQuery);
            } else if (groupedRequest) {
                List<String> groupColumns = new ArrayList<>();
                for (ColumnVO vo : safeRequest.rowGroupCols()) {
                    groupColumns.add(vo.field());
                }
                List<SqlBuilder.GroupedAggregation> aggregations;
                try {
                    aggregations = ReportController.sanitizeAggregations(
                            safeRequest.valueCols(), aggregatableFields, visibleColDefs);
                } catch (IllegalArgumentException iae) {
                    return badRequest("INVALID_AGGREGATION_REQUEST", iae.getMessage());
                }
                if (aggregations.isEmpty()) {
                    return badRequest("INVALID_AGGREGATION_REQUEST",
                            "Grouped export requires at least one aggregatable value column");
                }
                exportQuery = queryEngine.buildGroupedExportQuery(
                        def, scopedAuthz, groupColumns, aggregations,
                        filterModel, sortModel);
                exportColumns = groupedExportColumns(groupColumns, columnDefByField, aggregations);
            } else if (safeRequest.requestsGrouping()) {
                // Grouping/pivot intent present but the shape doesn't
                // satisfy either classifier: fail-closed with the same
                // code the live /query path emits so the FE can render
                // a consistent error message.
                return badRequest("GROUPING_NOT_SUPPORTED",
                        "Export request shape is not supported: rowGroupCols/"
                                + "valueCols/pivotCols/pivotMode combination must "
                                + "either match the single-level pivot contract "
                                + "(pivotMode=true + 1 rowGroup + 1 pivotCol + value cols) "
                                + "or the grouped contract (>=1 rowGroup + >=1 value col, "
                                + "no pivotMode, no pivotCols)");
            } else {
                // Flat export — same shape as the legacy GET path.
                exportQuery = queryEngine.buildExportQuery(
                        def, scopedAuthz, filterModel, sortModel);
                List<String> visibleColumns = queryEngine.getVisibleColumns(def, scopedAuthz);
                exportColumns = flatExportColumns(visibleColumns, columnDefByField);
            }
        } catch (IllegalArgumentException iae) {
            return badRequest("INVALID_AGGREGATION_REQUEST", iae.getMessage());
        }

        String userId = jwt != null ? JwtClaimExtractor.extractAuditUsername(jwt) : authz.getUserId();
        auditClient.logReportExport(key, authz.getUserId(), userId, format);

        HttpHeaders degradationHeaders =
                com.example.report.query.DegradationHeaders.of(exportQuery.warnings());

        SqlBuilder.BuiltQuery finalQuery = exportQuery;
        List<ExportColumn> finalColumns = exportColumns;

        if ("excel".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            StreamingResponseBody body = out ->
                    ExcelStreamingExporter.exportWithColumns(jdbc, finalQuery, finalColumns, def.title(), out);
            return ResponseEntity.ok()
                    .headers(degradationHeaders)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + key + ".xlsx\"")
                    .body(body);
        }

        StreamingResponseBody body = out ->
                CsvStreamingExporter.exportWithColumns(jdbc, finalQuery, finalColumns, out);
        return ResponseEntity.ok()
                .headers(degradationHeaders)
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + key + ".csv\"")
                .body(body);
    }

    /**
     * Classify the request as a valid single-level pivot export shape.
     * Reuses the live {@code /query} contract via
     * {@link ReportController#singleLevelPivotRequest(ReportQueryRequestDto,
     * java.util.Set, java.util.Set)} so the two paths stay in lockstep.
     */
    private boolean isPivotRequest(ReportExportRequestDto req,
                                    java.util.Set<String> groupableFields,
                                    java.util.Set<String> pivotableFields) {
        return ReportController.singleLevelPivotRequest(
                toQueryRequest(req), groupableFields, pivotableFields);
    }

    /**
     * Grouped export shape: at least one rowGroupCol (all groupable),
     * at least one valueCol, no pivotMode, no pivotCols, no groupKeys
     * (export ships every bucket — the user's expansion frontier is
     * not relevant).
     */
    private boolean isGroupedRequest(ReportExportRequestDto req,
                                      java.util.Set<String> groupableFields) {
        if (req.rowGroupCols() == null || req.rowGroupCols().isEmpty()) return false;
        if (req.rowGroupCols().size() > ReportController.MAX_ROW_GROUP_DEPTH) return false;
        if (req.valueCols() == null || req.valueCols().isEmpty()) return false;
        if (Boolean.TRUE.equals(req.pivotMode())) return false;
        if (req.pivotCols() != null && !req.pivotCols().isEmpty()) return false;
        java.util.Set<String> seenFields = new java.util.HashSet<>();
        for (ColumnVO vo : req.rowGroupCols()) {
            if (vo == null || vo.field() == null) return false;
            if (!groupableFields.contains(vo.field())) return false;
            if (!seenFields.add(vo.field())) return false;
        }
        return true;
    }

    /**
     * Adapt the export DTO to the query DTO shape so the live
     * classifier helper can be reused without duplicating its logic.
     * groupKeys/startRow/endRow are intentionally absent on export.
     */
    private ReportQueryRequestDto toQueryRequest(ReportExportRequestDto req) {
        return new ReportQueryRequestDto(
                null, null,
                req.rowGroupCols(),
                req.valueCols(),
                req.pivotCols(),
                req.pivotMode(),
                java.util.List.of(),
                req.filterModel(),
                req.sortModel());
    }

    /**
     * PR-0.5b post-deploy fix (Codex thread 019e2cd7, live cluster smoke
     * 2026-05-15 19:25): wrap structured 400s in a
     * {@link StreamingResponseBody} so the method signature can stay
     * {@code ResponseEntity<StreamingResponseBody>} (single type → no
     * generic-erasure converter resolution failure for the happy-path
     * binary body).
     *
     * <p>The original {@code ResponseEntity<?>} happy-path body bound
     * to {@code StreamingResponseBody} at runtime, but Spring's
     * {@code HttpEntityMethodProcessor} resolves converter at the
     * erased {@code Object} class — no message converter matches the
     * lambda type at {@code application/vnd.openxmlformats…} content
     * type, so the request 500s with
     * {@code HttpMessageNotWritableException}.
     *
     * <p>The FE Blob → JSON parser doesn't care that the body
     * arrived through a {@link StreamingResponseBody} indirection
     * (the wire bytes are still the canonical
     * {@code {"code":"...","message":"..."}} envelope), so this is
     * a server-side serialisation fix only — FE contract unchanged.
     */
    private ResponseEntity<StreamingResponseBody> badRequest(String code, String message) {
        ReportQueryErrorDto err = new ReportQueryErrorDto(code, message);
        StreamingResponseBody body = out -> {
            byte[] bytes = objectMapper.writeValueAsBytes(err);
            out.write(bytes);
        };
        return ResponseEntity.badRequest()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(body);
    }

    /**
     * Build the {@link ExportColumn} list for a flat export — header
     * uses the registry's {@code headerName} when present, otherwise
     * falls back to the raw field.
     */
    private List<ExportColumn> flatExportColumns(
            List<String> visibleColumns,
            Map<String, ColumnDefinition> columnDefByField) {
        List<ExportColumn> out = new ArrayList<>();
        for (String field : visibleColumns) {
            ColumnDefinition cd = columnDefByField.get(field);
            String header = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : field;
            out.add(new ExportColumn(field, header));
        }
        return out;
    }

    /**
     * Build the {@link ExportColumn} list for a grouped (non-pivot)
     * export: every group column + {@code _rowCount} + each
     * aggregation alias.
     */
    private List<ExportColumn> groupedExportColumns(
            List<String> groupColumns,
            Map<String, ColumnDefinition> columnDefByField,
            List<SqlBuilder.GroupedAggregation> aggregations) {
        List<ExportColumn> out = new ArrayList<>();
        for (String groupCol : groupColumns) {
            ColumnDefinition cd = columnDefByField.get(groupCol);
            String header = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : groupCol;
            out.add(new ExportColumn(groupCol, header));
        }
        out.add(new ExportColumn("_rowCount", "#"));
        for (SqlBuilder.GroupedAggregation agg : aggregations) {
            ColumnDefinition cd = columnDefByField.get(agg.field());
            String valueLabel = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : agg.field();
            String header = agg.func().toUpperCase(java.util.Locale.ROOT)
                    + "(" + valueLabel + ")";
            out.add(new ExportColumn(agg.field(), header));
        }
        return out;
    }

    /**
     * Build the {@link ExportColumn} list for a pivot export:
     * {@code groupColumn + _rowCount + (<pivotLabel> / <AGG>(<valueLabel>))*}.
     */
    private List<ExportColumn> pivotExportColumns(
            String groupColumn,
            Map<String, ColumnDefinition> columnDefByField,
            SqlBuilder.PivotedBuiltQuery pivotQuery) {
        List<ExportColumn> out = new ArrayList<>();
        ColumnDefinition groupCd = columnDefByField.get(groupColumn);
        String groupHeader = groupCd != null && groupCd.headerName() != null
                && !groupCd.headerName().isBlank()
                ? groupCd.headerName() : groupColumn;
        out.add(new ExportColumn(groupColumn, groupHeader));
        out.add(new ExportColumn("_rowCount", "#"));
        for (com.example.report.query.PivotResultColumn prc : pivotQuery.pivotResultColumns()) {
            ColumnDefinition valueCd = columnDefByField.get(prc.valueField());
            String valueLabel = valueCd != null && valueCd.headerName() != null
                    && !valueCd.headerName().isBlank()
                    ? valueCd.headerName() : prc.valueField();
            String header = prc.pivotLabel() + " / "
                    + prc.aggFunc().toUpperCase(java.util.Locale.ROOT)
                    + "(" + valueLabel + ")";
            out.add(new ExportColumn(prc.field(), header));
        }
        return out;
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSON parameter: {}", e.getMessage());
            return null;
        }
    }
}
