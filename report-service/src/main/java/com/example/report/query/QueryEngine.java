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
     * PR-0.4b (Codex thread {@code 019e2695}): execute a single-level
     * pivoted GROUP BY query. SQL shape and contract are documented on
     * {@link SqlBuilder#buildPivotedGroupedQuery}.
     *
     * <p>The returned {@link PagedData#pivotResultFields()} carries the
     * ordered alias list AG Grid SSRM expects on its {@code
     * pivotResultFields} response field. {@code total} is the count of
     * distinct group buckets (same semantic as {@link #executeGroupedQuery}).
     */
    public PagedData executePivotedGroupedQuery(
            ReportDefinition def,
            AuthzMeResponse authz,
            String groupColumn,
            String pivotColumn,
            List<com.example.report.registry.PivotValue> pivotValues,
            List<SqlBuilder.GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            List<Map<String, String>> sortModel,
            int page,
            int pageSize) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        YearlySchemaResolver.ResolvedSchemas schemas =
                resolveSchemas(def, authz, agGridFilter);

        SqlBuilder.PivotedBuiltQuery dataQuery = sqlBuilder.buildPivotedGroupedQuery(
                def, schemas, visibleColumns,
                groupColumn, pivotColumn, pivotValues, aggregations,
                agGridFilter, sortModel,
                rls.whereClause(), rls.params(), page, pageSize);

        log.debug("Pivoted report query [{} GROUP BY {} PIVOT {}]: {}",
                def.key(), groupColumn, pivotColumn, dataQuery.sql());

        List<Map<String, Object>> items =
                jdbc.queryForList(dataQuery.sql(), dataQuery.params());

        long total = -1;
        try {
            SqlBuilder.BuiltQuery countQuery = sqlBuilder.buildGroupedCountQuery(
                    def, schemas, visibleColumns, groupColumn, agGridFilter,
                    rls.whereClause(), rls.params());
            Long count = jdbc.queryForObject(countQuery.sql(), countQuery.params(), Long.class);
            total = count != null ? count : -1;
        } catch (Exception e) {
            log.warn("Pivoted count query failed for report {} (groupBy={}, pivot={}): {}",
                    def.key(), groupColumn, pivotColumn, e.getMessage());
        }

        return new PagedData(items, total, page, pageSize,
                dataQuery.warnings(),
                dataQuery.pivotResultFields(),
                dataQuery.pivotResultColumns());
    }

    /**
     * Codex 019e0c99 iter-3 §C absorb: warnings ride along the result so
     * controllers can lift them onto response headers (e.g.
     * {@code X-Report-Degraded}). Backward-compat constructors let
     * pre-existing callers stay (warnings + pivotResultFields default
     * to empty).
     *
     * <p>PR-0.4b (Codex thread {@code 019e2695}): {@code pivotResultFields}
     * carries the ordered alias list emitted by
     * {@link SqlBuilder#buildPivotedGroupedQuery}. Non-pivot execution
     * paths default the list to empty so {@link PagedData} stays the
     * single response envelope for grouped, flat, and pivoted queries.
     */
    public record PagedData(
            List<Map<String, Object>> items,
            long total,
            int page,
            int pageSize,
            List<DegradationWarning> warnings,
            List<String> pivotResultFields,
            List<PivotResultColumn> pivotResultColumns,
            Map<String, Object> grandTotalRow) {

        public PagedData(List<Map<String, Object>> items, long total, int page, int pageSize) {
            this(items, total, page, pageSize, List.of(), List.of(), List.of(), null);
        }

        public PagedData(List<Map<String, Object>> items, long total,
                          int page, int pageSize,
                          List<DegradationWarning> warnings) {
            this(items, total, page, pageSize, warnings, List.of(), List.of(), null);
        }

        public PagedData(List<Map<String, Object>> items, long total,
                          int page, int pageSize,
                          List<DegradationWarning> warnings,
                          List<String> pivotResultFields) {
            this(items, total, page, pageSize, warnings, pivotResultFields, List.of(), null);
        }

        public PagedData(List<Map<String, Object>> items, long total,
                          int page, int pageSize,
                          List<DegradationWarning> warnings,
                          List<String> pivotResultFields,
                          List<PivotResultColumn> pivotResultColumns) {
            this(items, total, page, pageSize, warnings, pivotResultFields,
                    pivotResultColumns, null);
        }

        public PagedData {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            pivotResultFields = pivotResultFields == null
                    ? List.of()
                    : List.copyOf(pivotResultFields);
            pivotResultColumns = pivotResultColumns == null
                    ? List.of()
                    : List.copyOf(pivotResultColumns);
            // PR-0.5a (Codex thread 019e2c61): grand total row stays
            // nullable so the @JsonInclude(NON_EMPTY) DTO path can
            // omit the field entirely on the dominant non-grouped /
            // child-store request shapes. Defensive immutability —
            // copy on construction so a later caller mutation can't
            // poison the response.
            //
            // Codex iter post-impl §High (thread 019e2c61): JDBC
            // aggregate rows legitimately carry NULL values (empty
            // filter set → SUM/AVG/STDEV null; weightedavg denominator
            // zero → null; percentile over empty set → null). Map.copyOf
            // rejects null values → NPE in canonical constructor would
            // break the dominant grouped flow. Use null-preserving
            // immutable wrapper instead.
            grandTotalRow = immutableNullableValueMap(grandTotalRow);
        }

        /**
         * Null-preserving immutable view: {@link Map#copyOf} rejects
         * null values, but JDBC aggregate rows legitimately carry
         * nulls (empty filter set, weightedavg denominator zero,
         * percentile over empty set, etc.). We need an immutable
         * wrapper that tolerates nulls so the canonical constructor
         * cannot NPE on grand-total responses. Empty maps collapse
         * to {@code null} so the {@code @JsonInclude(NON_NULL)} DTO
         * path can omit the field entirely.
         */
        private static Map<String, Object> immutableNullableValueMap(Map<String, Object> in) {
            if (in == null || in.isEmpty()) {
                return null;
            }
            return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(in));
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

    /**
     * PR-0.5a (2026-05-15, Codex thread {@code 019e2c61}): emit a
     * single grand-total aggregation row over the RLS+filter-narrowed
     * source set. Called by the controller in addition to
     * {@link #executeGroupedQuery} only on root SSRM store requests
     * (currentLevel==0, non-pivot, non-empty aggregations). Returns
     * {@code null} on any failure path so the dominant grouped flow
     * keeps rendering even if the grand total fails transiently.
     */
    public Map<String, Object> executeGrandTotalRow(
            ReportDefinition def,
            AuthzMeResponse authz,
            List<SqlBuilder.GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter) {
        if (aggregations == null || aggregations.isEmpty()) {
            return null;
        }
        try {
            List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
            RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);
            YearlySchemaResolver.ResolvedSchemas schemas =
                    resolveSchemas(def, authz, agGridFilter);

            SqlBuilder.BuiltQuery grandQuery = sqlBuilder.buildGrandTotalQuery(
                    def, schemas, visibleColumns, aggregations,
                    agGridFilter, rls.whereClause(), rls.params());
            log.debug("Grand-total report query [{}]: {}", def.key(), grandQuery.sql());
            List<Map<String, Object>> rows =
                    jdbc.queryForList(grandQuery.sql(), grandQuery.params());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("Grand-total query failed for report {}: {}", def.key(), e.getMessage());
            return null;
        }
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

    /**
     * PR-0.5b (Codex thread 019e2cd7): multi-level grouped export.
     * Returns the same {@link SqlBuilder.BuiltQuery} shape the flat
     * export uses; the controller layer wires {@code _rowCount + agg
     * aliases} into the streaming exporter.
     */
    public SqlBuilder.BuiltQuery buildGroupedExportQuery(
            ReportDefinition def,
            AuthzMeResponse authz,
            List<String> groupColumns,
            List<SqlBuilder.GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            List<Map<String, String>> sortModel) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        return sqlBuilder.buildGroupedExportQuery(
                def, schemas, visibleColumns, groupColumns, aggregations,
                agGridFilter, sortModel,
                rls.whereClause(), rls.params(), maxExportRows);
    }

    /**
     * PR-0.5b (Codex thread 019e2cd7): single-level pivot export.
     * Returns a {@link SqlBuilder.PivotedBuiltQuery} carrying the
     * {@code pivotResultColumns} metadata; the controller layer maps
     * each into an {@code ExportColumn(field, header)} pair.
     */
    public SqlBuilder.PivotedBuiltQuery buildPivotedGroupedExportQuery(
            ReportDefinition def,
            AuthzMeResponse authz,
            String groupColumn,
            String pivotColumn,
            List<com.example.report.registry.PivotValue> pivotValues,
            List<SqlBuilder.GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            List<Map<String, String>> sortModel) {
        List<String> visibleColumns = columnFilter.getVisibleColumns(def, authz);
        RowFilterInjector.RlsResult rls = rowFilterInjector.buildRlsClause(def, authz);

        YearlySchemaResolver.ResolvedSchemas schemas = resolveSchemas(def, authz, agGridFilter);

        return sqlBuilder.buildPivotedGroupedExportQuery(
                def, schemas, visibleColumns, groupColumn, pivotColumn,
                pivotValues, aggregations, agGridFilter, sortModel,
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
