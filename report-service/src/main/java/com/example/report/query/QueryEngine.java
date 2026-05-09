package com.example.report.query;

import com.example.report.access.ColumnFilter;
import com.example.report.access.RowFilterInjector;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ColumnFilter columnFilter;
    private final RowFilterInjector rowFilterInjector;
    private final YearlySchemaResolver yearlySchemaResolver;
    private final CurrentTenantSchemaResolver currentTenantSchemaResolver;
    private final com.example.report.registry.ReportRegistry reportRegistry;
    private final SqlBuilder sqlBuilder = new SqlBuilder();

    @Value("${report.query.max-export-rows:500000}")
    private int maxExportRows;

    public QueryEngine(NamedParameterJdbcTemplate jdbc,
                       ColumnFilter columnFilter,
                       RowFilterInjector rowFilterInjector,
                       YearlySchemaResolver yearlySchemaResolver,
                       CurrentTenantSchemaResolver currentTenantSchemaResolver,
                       com.example.report.registry.ReportRegistry reportRegistry) {
        this.jdbc = jdbc;
        this.columnFilter = columnFilter;
        this.rowFilterInjector = rowFilterInjector;
        this.yearlySchemaResolver = yearlySchemaResolver;
        this.currentTenantSchemaResolver = currentTenantSchemaResolver;
        this.reportRegistry = reportRegistry;
    }

    /**
     * Codex 019e0c99 iter-3 §C absorb: warnings ride along the result so
     * controllers can lift them onto response headers (e.g.
     * {@code X-Report-Degraded}). Backward-compat constructor lets pre-existing
     * callers stay (warnings default to empty).
     */
    public record PagedData(
            List<Map<String, Object>> items,
            long total,
            int page,
            int pageSize,
            List<DegradationWarning> warnings) {

        public PagedData(List<Map<String, Object>> items, long total, int page, int pageSize) {
            this(items, total, page, pageSize, List.of());
        }

        public PagedData {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public PagedData executeQuery(ReportDefinition def,
                                   AuthzMeResponse authz,
                                   Map<String, Object> agGridFilter,
                                   List<Map<String, String>> sortModel,
                                   int page,
                                   int pageSize) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        // Resolve year schemas for yearly reports
        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        SqlBuilder.BuiltQuery dataQuery = sqlBuilder.buildDataQuery(
                def, schemas, visibleColumns, agGridFilter, sortModel,
                rls.whereClause(), rls.params(), page, pageSize);

        log.debug("Report query [{}]: {}", def.key(), dataQuery.sql());

        List<Map<String, Object>> items = jdbc.queryForList(dataQuery.sql(), dataQuery.params());

        long total = getCount(def, schemas, agGridFilter, visibleColumns, rls);

        return new PagedData(items, total, page, pageSize, dataQuery.warnings());
    }

    /**
     * Execute a single-level GROUP BY query for AG Grid SSRM (PR-0.2
     * reporting hardening). The shape of the SQL is documented on
     * {@link SqlBuilder#buildGroupedQuery(ReportDefinition,
     * YearlySchemaResolver.ResolvedSchemas, List, String, List, Map,
     * List, String, MapSqlParameterSource, int, int)}.
     *
     * <p>The {@code total} surfaced on the {@link PagedData} is the
     * number of distinct group buckets, not the source row count, so
     * AG Grid's SSRM scrollbar reflects how many groups are reachable.
     */
    public PagedData executeGroupedQuery(ReportDefinition def,
                                          AuthzMeResponse authz,
                                          String groupColumn,
                                          List<SqlBuilder.GroupedAggregation> aggregations,
                                          Map<String, Object> agGridFilter,
                                          List<Map<String, String>> sortModel,
                                          int page,
                                          int pageSize) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        SqlBuilder.BuiltQuery dataQuery = sqlBuilder.buildGroupedQuery(
                def, schemas, visibleColumns, groupColumn, aggregations,
                agGridFilter, sortModel,
                rls.whereClause(), rls.params(), page, pageSize);

        log.debug("Grouped report query [{} GROUP BY {}]: {}",
                def.key(), groupColumn, dataQuery.sql());

        List<Map<String, Object>> items = jdbc.queryForList(dataQuery.sql(), dataQuery.params());

        long total = -1;
        try {
            SqlBuilder.BuiltQuery countQuery = sqlBuilder.buildGroupedCountQuery(
                    def, schemas, visibleColumns, groupColumn, agGridFilter,
                    rls.whereClause(), rls.params());
            Long count = jdbc.queryForObject(countQuery.sql(), countQuery.params(), Long.class);
            total = count != null ? count : -1;
        } catch (Exception e) {
            log.warn("Grouped count query failed for report {} (groupBy={}): {}",
                    def.key(), groupColumn, e.getMessage());
        }

        return new PagedData(items, total, page, pageSize, dataQuery.warnings());
    }

    public SqlBuilder.BuiltQuery buildExportQuery(ReportDefinition def,
                                                    AuthzMeResponse authz,
                                                    Map<String, Object> agGridFilter,
                                                    List<Map<String, String>> sortModel) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        return sqlBuilder.buildExportQuery(
                def, schemas, visibleColumns, agGridFilter, sortModel,
                rls.whereClause(), rls.params(), maxExportRows);
    }

    public List<String> getVisibleColumns(ReportDefinition def, AuthzMeResponse authz) {
        return columnFilter.getVisibleColumns(def, authz);
    }

    private YearlySchemaResolver.ResolvedSchemas resolveSchemas(ReportDefinition def,
                                                                  AuthzMeResponse authz,
                                                                  Map<String, Object> agGridFilter) {
        if (def.isYearlySchema()) {
            return yearlySchemaResolver.resolve(def, authz, agGridFilter);
        }
        // Codex 019e0d06 iter-3 §1 BLOCKER absorb: dispatch authoritative
        // signal is `schemaMode`, NOT the side-channel tenantBoundary.
        // Side-channel mismatch (typo/missing schemaResolver) used to fall
        // through to legacy null path, which would render `[null].[TABLE]`.
        // Now schemaMode=current always routes to currentTenantSchemaResolver;
        // ReportRegistry.validate enforces tenantBoundary.schemaResolver ==
        // workcube-current-company at startup (fail-closed).
        if ("current".equals(def.schemaMode())) {
            return currentTenantSchemaResolver.resolve(def, authz);
        }
        return null; // legacy static — SqlBuilder uses def.sourceSchema() directly
    }

    private long getCount(ReportDefinition def,
                          YearlySchemaResolver.ResolvedSchemas schemas,
                          Map<String, Object> agGridFilter,
                          List<String> visibleColumns,
                          RowFilterInjector.RlsResult rls) {
        try {
            SqlBuilder.BuiltQuery countQuery = sqlBuilder.buildCountQuery(
                    def, schemas, agGridFilter, visibleColumns, rls.whereClause(), rls.params());
            Long count = jdbc.queryForObject(countQuery.sql(), countQuery.params(), Long.class);
            return count != null ? count : -1;
        } catch (Exception e) {
            log.warn("Count query failed for report {}: {}", def.key(), e.getMessage());
            return -1;
        }
    }
}
