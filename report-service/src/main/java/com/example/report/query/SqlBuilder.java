package com.example.report.query;

import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public class SqlBuilder {

    public record BuiltQuery(String sql, MapSqlParameterSource params) {}

    private final FilterTranslator filterTranslator = new FilterTranslator();
    private final SortTranslator sortTranslator = new SortTranslator();

    // ── Single-schema queries (original behavior) ──────────────────────

    public BuiltQuery buildDataQuery(ReportDefinition def,
                                      List<String> visibleColumns,
                                      Map<String, Object> agGridFilter,
                                      List<Map<String, String>> sortModel,
                                      String rlsWhereClause,
                                      MapSqlParameterSource rlsParams,
                                      int page,
                                      int pageSize) {
        return buildDataQuery(def, null, visibleColumns, agGridFilter, sortModel,
                rlsWhereClause, rlsParams, page, pageSize);
    }

    public BuiltQuery buildCountQuery(ReportDefinition def,
                                       Map<String, Object> agGridFilter,
                                       List<String> visibleColumns,
                                       String rlsWhereClause,
                                       MapSqlParameterSource rlsParams) {
        return buildCountQuery(def, null, agGridFilter, visibleColumns,
                rlsWhereClause, rlsParams);
    }

    public BuiltQuery buildExportQuery(ReportDefinition def,
                                        List<String> visibleColumns,
                                        Map<String, Object> agGridFilter,
                                        List<Map<String, String>> sortModel,
                                        String rlsWhereClause,
                                        MapSqlParameterSource rlsParams,
                                        int maxRows) {
        return buildExportQuery(def, null, visibleColumns, agGridFilter, sortModel,
                rlsWhereClause, rlsParams, maxRows);
    }

    // ── Multi-schema (UNION ALL) queries ───────────────────────────────

    public BuiltQuery buildDataQuery(ReportDefinition def,
                                      YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                      List<String> visibleColumns,
                                      Map<String, Object> agGridFilter,
                                      List<Map<String, String>> sortModel,
                                      String rlsWhereClause,
                                      MapSqlParameterSource rlsParams,
                                      int page,
                                      int pageSize) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        String selectCols = visibleColumns.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectCols);
        sql.append(" FROM ").append(fromClause);

        String orderBy = sortTranslator.translate(sortModel, allowedCols, def.defaultSort(), def.defaultSortDirection());
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        } else {
            sql.append(" ORDER BY (SELECT NULL)");
        }

        int offset = (page - 1) * pageSize;
        sql.append(" OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
        params.addValue("_offset", offset);
        params.addValue("_pageSize", pageSize);

        return new BuiltQuery(sql.toString(), params);
    }

    public BuiltQuery buildCountQuery(ReportDefinition def,
                                       YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                       Map<String, Object> agGridFilter,
                                       List<String> visibleColumns,
                                       String rlsWhereClause,
                                       MapSqlParameterSource rlsParams) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        // For count, we just need * from the union
        String fromClause = buildFromClause(def, resolvedSchemas, "*",
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ").append(fromClause);

        return new BuiltQuery(sql.toString(), params);
    }

    public BuiltQuery buildExportQuery(ReportDefinition def,
                                        YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                        List<String> visibleColumns,
                                        Map<String, Object> agGridFilter,
                                        List<Map<String, String>> sortModel,
                                        String rlsWhereClause,
                                        MapSqlParameterSource rlsParams,
                                        int maxRows) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        String selectCols = visibleColumns.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TOP(:_maxRows) ").append(selectCols);
        sql.append(" FROM ").append(fromClause);
        params.addValue("_maxRows", maxRows);

        String orderBy = sortTranslator.translate(sortModel, allowedCols, def.defaultSort(), def.defaultSortDirection());
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        }

        return new BuiltQuery(sql.toString(), params);
    }

    // ── Grouping queries (PR-0.2 single-level GROUP BY) ────────────────

    /**
     * Allowed AG Grid SSRM aggregation function tokens. Both the request
     * payload and the column registry's {@code defaultAggFunc} normalise
     * to lower-case before comparing, so the set is canonical.
     */
    private static final Set<String> ALLOWED_AGG_FUNCS = Set.of(
            "sum", "avg", "min", "max", "count");

    /**
     * Specifies a single value-column aggregation for
     * {@link #buildGroupedQuery(ReportDefinition, YearlySchemaResolver.ResolvedSchemas,
     * List, String, List, Map, List, String, MapSqlParameterSource, int, int)}.
     * The SQL builder emits {@code <func>([field]) AS [field]} so the
     * aggregated column shadows the raw field on the response row, matching
     * AG Grid's convention.
     *
     * @param field SQL column name (must be in the visible-columns
     *              allow-list).
     * @param func  Aggregation function (sum / avg / min / max / count).
     */
    public record GroupedAggregation(String field, String func) {
        public GroupedAggregation {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException(
                        "GroupedAggregation field must not be blank");
            }
            if (func == null || func.isBlank()) {
                throw new IllegalArgumentException(
                        "GroupedAggregation func must not be blank");
            }
            String normalized = func.trim().toLowerCase();
            if (!ALLOWED_AGG_FUNCS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "GroupedAggregation func must be one of "
                                + ALLOWED_AGG_FUNCS + ", got: " + func);
            }
            func = normalized;
        }
    }

    /**
     * Build the SSRM single-level GROUP BY query (PR-0.2 reporting hardening).
     *
     * <p>SQL shape (Microsoft SQL Server T-SQL):
     * <pre>{@code
     * SELECT [groupColumn] AS [groupColumn],
     *        COUNT(*) AS [_rowCount],
     *        SUM([valueA]) AS [valueA],
     *        AVG([valueB]) AS [valueB]
     *   FROM <fromClause>
     *  GROUP BY [groupColumn]
     *  ORDER BY [groupColumn] ASC
     *  OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY;
     * }</pre>
     *
     * <p>The {@code _rowCount} column is always emitted so AG Grid can
     * surface the leaf-row count under each group node without a separate
     * round-trip. Aggregations that target a column already chosen as the
     * group dimension are silently dropped (they would produce a
     * tautological {@code GROUP BY x; SUM(x)}).
     *
     * <p>Caller contract:
     * <ul>
     *   <li>{@code groupColumn} must be in {@code visibleColumns}.</li>
     *   <li>Each {@link GroupedAggregation#field()} must be in
     *       {@code visibleColumns}; otherwise it is dropped (defence in
     *       depth — the controller already enforces this against the
     *       registry's {@code aggregatable} flag).</li>
     *   <li>Sort, RLS and filter handling mirror
     *       {@link #buildDataQuery(ReportDefinition, YearlySchemaResolver.ResolvedSchemas,
     *       List, Map, List, String, MapSqlParameterSource, int, int)}.</li>
     * </ul>
     */
    public BuiltQuery buildGroupedQuery(ReportDefinition def,
                                         YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                         List<String> visibleColumns,
                                         String groupColumn,
                                         List<GroupedAggregation> aggregations,
                                         Map<String, Object> agGridFilter,
                                         List<Map<String, String>> sortModel,
                                         String rlsWhereClause,
                                         MapSqlParameterSource rlsParams,
                                         int page,
                                         int pageSize) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        if (groupColumn == null || !allowedCols.contains(groupColumn)) {
            throw new IllegalArgumentException(
                    "groupColumn must be one of the visible columns, got: " + groupColumn);
        }

        // The columns referenced inside the FROM/UNION subquery — must
        // include the group column and every aggregation target so the
        // outer GROUP BY/aggregate can reach them.
        Set<String> projected = new java.util.LinkedHashSet<>();
        projected.add(groupColumn);
        List<GroupedAggregation> sanitized = new java.util.ArrayList<>();
        for (GroupedAggregation a : aggregations != null ? aggregations : List.<GroupedAggregation>of()) {
            if (!allowedCols.contains(a.field())) continue;
            if (a.field().equals(groupColumn)) continue; // tautological
            projected.add(a.field());
            sanitized.add(a);
        }

        String selectCols = projected.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT [").append(groupColumn).append("]");
        sql.append(", COUNT(*) AS [_rowCount]");
        for (GroupedAggregation a : sanitized) {
            sql.append(", ").append(a.func().toUpperCase())
               .append("([").append(a.field()).append("])")
               .append(" AS [").append(a.field()).append("]");
        }
        sql.append(" FROM ").append(fromClause);
        sql.append(" GROUP BY [").append(groupColumn).append("]");

        // The order-by on a grouped query may only reference the group
        // column or an aggregation alias. PR-0.2 keeps this simple: if
        // the client picks the group column, honor it; otherwise default
        // to ascending on the group column for deterministic pagination.
        String orderBy = translateGroupedSort(sortModel, groupColumn, sanitized);
        sql.append(" ORDER BY ").append(orderBy);

        int offset = (page - 1) * pageSize;
        sql.append(" OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
        params.addValue("_offset", offset);
        params.addValue("_pageSize", pageSize);

        return new BuiltQuery(sql.toString(), params);
    }

    /**
     * Count of distinct group buckets — used by SSRM to size the
     * pagination scrollbar. Does NOT count source rows; returns the
     * cardinality of {@code COUNT(DISTINCT [groupColumn])} via a
     * subquery wrapper to stay portable across the single-schema and
     * UNION ALL FROM clauses.
     */
    public BuiltQuery buildGroupedCountQuery(ReportDefinition def,
                                              YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                              List<String> visibleColumns,
                                              String groupColumn,
                                              Map<String, Object> agGridFilter,
                                              String rlsWhereClause,
                                              MapSqlParameterSource rlsParams) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        if (groupColumn == null || !allowedCols.contains(groupColumn)) {
            throw new IllegalArgumentException(
                    "groupColumn must be one of the visible columns, got: " + groupColumn);
        }

        String selectCols = "[" + groupColumn + "]";

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM (");
        sql.append("SELECT [").append(groupColumn).append("]");
        sql.append(" FROM ").append(fromClause);
        sql.append(" GROUP BY [").append(groupColumn).append("]");
        sql.append(") AS _g");

        return new BuiltQuery(sql.toString(), params);
    }

    /**
     * Resolve the {@code ORDER BY} clause for a grouped query.
     *
     * <p>Allowed sort fields are the group column and any aggregation
     * alias. Unknown / disallowed entries are skipped silently. If the
     * resulting list is empty, returns an ascending sort on the group
     * column so paging is deterministic.
     */
    private String translateGroupedSort(List<Map<String, String>> sortModel,
                                         String groupColumn,
                                         List<GroupedAggregation> aggregations) {
        if (sortModel == null || sortModel.isEmpty()) {
            return "[" + groupColumn + "] ASC";
        }
        Set<String> allowedSortCols = new java.util.HashSet<>();
        allowedSortCols.add(groupColumn);
        for (GroupedAggregation a : aggregations) {
            allowedSortCols.add(a.field());
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> entry : sortModel) {
            String colId = entry.get("colId");
            String dir = entry.get("sort");
            if (colId == null || !allowedSortCols.contains(colId)) continue;
            String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
            if (sb.length() > 0) sb.append(", ");
            sb.append("[").append(colId).append("] ").append(direction);
        }
        return sb.length() == 0 ? "[" + groupColumn + "] ASC" : sb.toString();
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Builds the FROM clause. For single-schema or non-yearly reports, returns:
     *   [schema].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *
     * For multi-year schemas, returns a subquery with UNION ALL:
     *   (SELECT cols FROM [schema1].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *    UNION ALL
     *    SELECT cols FROM [schema2].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *   ) AS _u
     *
     * WHERE push-down: filters and RLS are applied inside each UNION branch for performance.
     */
    private String buildFromClause(ReportDefinition def,
                                   YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                   String selectCols,
                                   String rlsWhereClause,
                                   MapSqlParameterSource rlsParams,
                                   FilterTranslator.FilterResult filterResult,
                                   MapSqlParameterSource params) {

        boolean isMultiSchema = resolvedSchemas != null && !resolvedSchemas.isSingle();

        if (!isMultiSchema) {
            // Single schema — original flat query (no subquery overhead)
            String schema = (resolvedSchemas != null && !resolvedSchemas.schemas().isEmpty())
                    ? resolvedSchemas.schemas().get(0)
                    : def.sourceSchema();

            StringBuilder sb = new StringBuilder();
            if (def.hasSourceQuery()) {
                // Custom SQL query — wrap as subquery
                String resolvedQuery = def.sourceQuery().replace("{schema}", schema);
                sb.append("(").append(resolvedQuery).append(") AS _src");
            } else {
                sb.append("[").append(schema).append("].[").append(def.source()).append("] WITH (NOLOCK)");
            }
            sb.append(" WHERE 1=1");
            appendWhereFilters(sb, rlsWhereClause, rlsParams, filterResult, params);
            return sb.toString();
        }

        // Multi-schema UNION ALL — wrap in subquery
        StringBuilder union = new StringBuilder();
        union.append("(\n");

        List<String> schemas = resolvedSchemas.schemas();
        for (int i = 0; i < schemas.size(); i++) {
            if (i > 0) {
                union.append("\n  UNION ALL\n");
            }
            union.append("  SELECT ").append(selectCols);
            if (def.hasSourceQuery()) {
                String resolvedQuery = def.sourceQuery().replace("{schema}", schemas.get(i));
                union.append(" FROM (").append(resolvedQuery).append(") AS _src");
            } else {
                union.append(" FROM [").append(schemas.get(i)).append("].[").append(def.source()).append("] WITH (NOLOCK)");
            }
            union.append(" WHERE 1=1");
            // Push down RLS and filters into each branch
            appendWhereFiltersInline(union, rlsWhereClause, filterResult);
        }

        union.append("\n) AS _u");

        // Merge params once (same params apply to all branches via named parameters)
        if (rlsParams != null) {
            mergeParams(params, rlsParams);
        }
        if (!filterResult.whereClause().isBlank()) {
            mergeParams(params, filterResult.params());
        }

        return union.toString();
    }

    /** Append WHERE fragments and merge params (for single-schema path). */
    private void appendWhereFilters(StringBuilder sql,
                                    String rlsWhereClause,
                                    MapSqlParameterSource rlsParams,
                                    FilterTranslator.FilterResult filterResult,
                                    MapSqlParameterSource params) {
        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
            if (rlsParams != null) {
                mergeParams(params, rlsParams);
            }
        }
        if (!filterResult.whereClause().isBlank()) {
            sql.append(" AND ").append(filterResult.whereClause());
            mergeParams(params, filterResult.params());
        }
    }

    /** Append WHERE fragments inline (no param merge — done once for UNION). */
    private void appendWhereFiltersInline(StringBuilder sql,
                                          String rlsWhereClause,
                                          FilterTranslator.FilterResult filterResult) {
        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
        }
        if (!filterResult.whereClause().isBlank()) {
            sql.append(" AND ").append(filterResult.whereClause());
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeParams(MapSqlParameterSource target, MapSqlParameterSource source) {
        Map<String, Object> sourceValues = source.getValues();
        sourceValues.forEach(target::addValue);
    }
}
