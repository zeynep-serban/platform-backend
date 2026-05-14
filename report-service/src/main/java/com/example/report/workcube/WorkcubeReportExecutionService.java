package com.example.report.workcube;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.access.ReportAccessEvaluator.AccessResult;
import com.example.report.access.RowFilterInjector;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.security.JwtClaimExtractor;
import com.example.report.dto.PagedResultDto;
import com.example.report.query.CurrentTenantSchemaResolver;
import com.example.report.query.YearlySchemaResolver;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 Program 11.3 — Workcube report execution orchestration
 * (Codex {@code 019e258f} iter-29 PARTIAL absorb).
 *
 * <p>Thin orchestration layer between {@link WorkcubeReportController}
 * adapter-backed endpoints and {@link WorkcubeQueryAdapter}. Composes:
 *
 * <ol>
 *   <li>{@link ReportRegistry#get} — 404 if unknown</li>
 *   <li>{@link PermissionResolver#getAuthzMe} — authz snapshot</li>
 *   <li>{@link ReportAccessEvaluator#evaluate} — DENIED → audit + 403 BEFORE
 *       narrow (report-level permission is tenant-independent)</li>
 *   <li>{@link CompanyHeaderScopeNarrower#narrow} — singleton COMPANY scope</li>
 *   <li>{@link ColumnFilter#getVisibleColumns} — column-level RLS</li>
 *   <li>{@link RowFilterInjector#buildRlsClause} — row-level RLS clause + params</li>
 *   <li>{@link YearlySchemaResolver}/{@link CurrentTenantSchemaResolver} — schema target</li>
 *   <li>AG Grid filter + sort JSON parse</li>
 *   <li>{@link WorkcubeQueryAdapter#executeData} — rendered SQL + V1 +
 *       composite tenant boundary + JDBC</li>
 *   <li>{@link ReportAuditClient#logReportAccess} — success audit</li>
 * </ol>
 *
 * <h2>Adım 11.4 — interim gate REMOVED</h2>
 * Class-level {@code @PreAuthorize} on {@link WorkcubeReportController}
 * REMOVED. Non-admin denial now happens at service level via
 * {@link ReportAccessEvaluator}{@code .evaluate(def, authz) == DENIED} branch.
 * Full authz pipeline (access evaluator + column filter + row filter +
 * audit) takes over the role previously held by the interim super-admin gate.
 *
 * @see WorkcubeQueryAdapter
 * @see WorkcubeReportController
 * @see WorkcubeAccessGuard (deprecated; legacy /views/* only)
 */
@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class WorkcubeReportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeReportExecutionService.class);

    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final ReportRegistry reportRegistry;
    private final PermissionResolver permissionResolver;
    private final CompanyHeaderScopeNarrower narrower;
    private final WorkcubeQueryAdapter adapter;
    private final YearlySchemaResolver yearlySchemaResolver;
    private final CurrentTenantSchemaResolver currentTenantSchemaResolver;
    private final ReportAccessEvaluator accessEvaluator;
    private final ColumnFilter columnFilter;
    private final RowFilterInjector rowFilterInjector;
    private final ReportAuditClient auditClient;
    private final ObjectMapper objectMapper;

    public WorkcubeReportExecutionService(ReportRegistry reportRegistry,
                                          PermissionResolver permissionResolver,
                                          CompanyHeaderScopeNarrower narrower,
                                          WorkcubeQueryAdapter adapter,
                                          YearlySchemaResolver yearlySchemaResolver,
                                          CurrentTenantSchemaResolver currentTenantSchemaResolver,
                                          ReportAccessEvaluator accessEvaluator,
                                          ColumnFilter columnFilter,
                                          RowFilterInjector rowFilterInjector,
                                          ReportAuditClient auditClient,
                                          ObjectMapper objectMapper) {
        this.reportRegistry = reportRegistry;
        this.permissionResolver = permissionResolver;
        this.narrower = narrower;
        this.adapter = adapter;
        this.yearlySchemaResolver = yearlySchemaResolver;
        this.currentTenantSchemaResolver = currentTenantSchemaResolver;
        this.accessEvaluator = accessEvaluator;
        this.columnFilter = columnFilter;
        this.rowFilterInjector = rowFilterInjector;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve the tenant-scoped schema set for the given report definition.
     * Codex iter-30 REVISE-1 blocker 1: yearly/current reports MUST go
     * through the resolver so the rendered SQL targets the correct tenant
     * schema (not the legacy hardcoded {@code def.sourceSchema()} fallback).
     */
    private YearlySchemaResolver.ResolvedSchemas resolveSchemas(ReportDefinition def,
                                                                AuthzMeResponse authz,
                                                                Map<String, Object> filter) {
        if (def.isYearlySchema()) {
            return yearlySchemaResolver.resolve(def, authz, filter);
        }
        if ("current".equals(def.schemaMode())) {
            return currentTenantSchemaResolver.resolve(def, authz);
        }
        return null;
    }

    public PagedResultDto<Map<String, Object>> executeData(String reportKey,
                                                           int page,
                                                           int pageSize,
                                                           String sortJson,
                                                           String filterJson,
                                                           String companyHeader,
                                                           Jwt jwt) {
        ReportDefinition def = findReportOrThrow(reportKey);
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        String auditUsername = JwtClaimExtractor.extractAuditUsername(jwt);

        // Codex iter-33 absorb: full authz check BEFORE narrow (report-level
        // permission/group is tenant-independent; narrow drives row/schema/columns).
        AccessResult access = accessEvaluator.evaluate(def, authz);
        if (access != AccessResult.ALLOWED) {
            auditClient.logReportAccessDenied(reportKey,
                    authz != null ? authz.getUserId() : null,
                    auditUsername, access.name());
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "report_access_denied: " + access.name());
        }

        AuthzMeResponse scopedAuthz = authz != null ? narrower.narrow(authz, companyHeader) : null;

        Map<String, Object> agGridFilter = parseMapJson(filterJson);
        List<Map<String, String>> sortModel = parseSortJson(sortJson);

        int boundedPageSize = Math.min(Math.max(pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 1);

        // Codex iter-33 absorb: scopedAuthz is single source of truth for
        // schema/RLS/columns. Authz before narrow used only for report-level
        // permission gate above.
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, scopedAuthz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, scopedAuthz);
        String rlsClause = rls.whereClause() != null ? rls.whereClause() : "";
        MapSqlParameterSource rlsParams = rls.params() != null ? rls.params() : new MapSqlParameterSource();

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, scopedAuthz, agGridFilter);

        List<Map<String, Object>> rows = adapter.executeData(
                def, schemas, visibleColumns, agGridFilter, sortModel,
                rlsClause, rlsParams,
                boundedPage, boundedPageSize);

        long total = adapter.executeCount(def, schemas, agGridFilter, visibleColumns,
                rlsClause, rlsParams);

        auditClient.logReportAccess(reportKey,
                scopedAuthz != null ? scopedAuthz.getUserId() : null,
                auditUsername);

        log.debug("Workcube execute report={} page={} pageSize={} rowCount={} total={}",
                reportKey, boundedPage, boundedPageSize, rows.size(), total);

        return new PagedResultDto<>(rows, total, boundedPage, boundedPageSize);
    }

    public long executeCount(String reportKey,
                             String filterJson,
                             String companyHeader,
                             Jwt jwt) {
        ReportDefinition def = findReportOrThrow(reportKey);
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);

        AccessResult access = accessEvaluator.evaluate(def, authz);
        if (access != AccessResult.ALLOWED) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, "report_access_denied: " + access.name());
        }

        AuthzMeResponse scopedAuthz = authz != null ? narrower.narrow(authz, companyHeader) : null;
        Map<String, Object> agGridFilter = parseMapJson(filterJson);

        List<String> visibleColumns = columnFilter.getVisibleColumns(def, scopedAuthz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, scopedAuthz);
        String rlsClause = rls.whereClause() != null ? rls.whereClause() : "";
        MapSqlParameterSource rlsParams = rls.params() != null ? rls.params() : new MapSqlParameterSource();

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, scopedAuthz, agGridFilter);

        return adapter.executeCount(def, schemas, agGridFilter, visibleColumns,
                rlsClause, rlsParams);
    }

    private ReportDefinition findReportOrThrow(String key) {
        return reportRegistry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "report_not_found: " + key));
    }

    private Map<String, Object> parseMapJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Workcube execute filter JSON parse failed (empty fallback): {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<Map<String, String>> parseSortJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("Workcube execute sort JSON parse failed (empty fallback): {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
