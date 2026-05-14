package com.example.report.workcube;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
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
 *   <li>{@link CompanyHeaderScopeNarrower#narrow} — singleton COMPANY scope</li>
 *   <li>AG Grid filter + sort JSON parse</li>
 *   <li>{@link WorkcubeQueryAdapter#executeData} — rendered SQL + V1 +
 *       composite tenant boundary + JDBC</li>
 * </ol>
 *
 * <h2>Adım 11.3 minimum scope</h2>
 * Interim {@code @PreAuthorize} on {@link WorkcubeReportController} guards
 * non-admin (Adım 1.5 interim gate). This service does NOT yet add
 * {@code ReportAccessEvaluator}, {@code ColumnFilter}, {@code RowFilterInjector},
 * or schema resolution — those land in Adım 11.4 when the interim gate
 * is removed and the full authz pipeline takes over.
 *
 * <p>Visible columns are derived directly from
 * {@link ReportDefinition#columns()} (no column-level RLS yet). This is
 * safe under the interim super-admin gate: only super-admins reach this
 * service in Adım 11.3.
 *
 * @see WorkcubeQueryAdapter
 * @see WorkcubeReportController
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
    private final ObjectMapper objectMapper;

    public WorkcubeReportExecutionService(ReportRegistry reportRegistry,
                                          PermissionResolver permissionResolver,
                                          CompanyHeaderScopeNarrower narrower,
                                          WorkcubeQueryAdapter adapter,
                                          YearlySchemaResolver yearlySchemaResolver,
                                          CurrentTenantSchemaResolver currentTenantSchemaResolver,
                                          ObjectMapper objectMapper) {
        this.reportRegistry = reportRegistry;
        this.permissionResolver = permissionResolver;
        this.narrower = narrower;
        this.adapter = adapter;
        this.yearlySchemaResolver = yearlySchemaResolver;
        this.currentTenantSchemaResolver = currentTenantSchemaResolver;
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
        if (authz != null) {
            authz = narrower.narrow(authz, companyHeader);
        }
        Map<String, Object> agGridFilter = parseMapJson(filterJson);
        List<Map<String, String>> sortModel = parseSortJson(sortJson);

        int boundedPageSize = Math.min(Math.max(pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 1);

        List<String> visibleColumns = def.columns().stream()
                .map(ColumnDefinition::field)
                .toList();

        // Codex iter-30 blocker 1: yearly/current reports route through
        // the resolver so X-Company-Id actually drives the schema target.
        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        List<Map<String, Object>> rows = adapter.executeData(
                def, schemas, visibleColumns, agGridFilter, sortModel,
                "", new MapSqlParameterSource(),
                boundedPage, boundedPageSize);

        // Codex iter-30 PagedResultDto.total fix: real count via adapter
        // count path (extra DB round-trip; matches /api/v1/reports contract).
        long total = adapter.executeCount(def, schemas, agGridFilter, visibleColumns,
                "", new MapSqlParameterSource());

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
        if (authz != null) {
            authz = narrower.narrow(authz, companyHeader);
        }
        Map<String, Object> agGridFilter = parseMapJson(filterJson);

        List<String> visibleColumns = def.columns().stream()
                .map(ColumnDefinition::field)
                .toList();

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        return adapter.executeCount(def, schemas, agGridFilter, visibleColumns,
                "", new MapSqlParameterSource());
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
