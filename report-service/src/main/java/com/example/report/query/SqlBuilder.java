package com.example.report.query;

import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public class SqlBuilder {

    /**
     * Built SQL + bound params + degradation warnings produced during
     * construction. Codex 019e0c99 iter-3 §C absorb: warnings ride along the
     * query record so controllers can dedupe by code and surface
     * {@code X-Report-Degraded} headers without ThreadLocal.
     *
     * <p>Compact constructor keeps {@code MapSqlParameterSource} type stable
     * (Codex iter-3 §2 mandate — no drift to {@code Map<String,Object>}).
     * Backward-compat constructor lets pre-existing callsites stay
     * (warnings default to empty list).
     */
    public record BuiltQuery(
            String sql,
            MapSqlParameterSource params,
            List<DegradationWarning> warnings) {

        /** Backward-compat: legacy callers without warning telemetry. */
        public BuiltQuery(String sql, MapSqlParameterSource params) {
            this(sql, params, List.of());
        }

        public BuiltQuery {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Internal helper: result of {@link #buildFromClause(...)}, carrying both
     * the SQL fragment and any degradation warnings collected per-branch (so
     * caller builders can attach them to the final {@link BuiltQuery}).
     */
    private record FromClauseResult(String sql, List<DegradationWarning> warnings) {}

    /** Codex iter-3: lookup table the placeholder targets (currently single). */
    private static final String SETUP_PROCESS_CAT_TABLE = "SETUP_PROCESS_CAT";

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

        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectCols);
        sql.append(" FROM ").append(fromResult.sql());

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

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
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
        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, "*",
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ").append(fromResult.sql());

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
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

        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TOP(:_maxRows) ").append(selectCols);
        sql.append(" FROM ").append(fromResult.sql());
        params.addValue("_maxRows", maxRows);

        String orderBy = sortTranslator.translate(sortModel, allowedCols, def.defaultSort(), def.defaultSortDirection());
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        }

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
    }

    // ── Grouping queries (PR-0.2 single-level GROUP BY) ────────────────

    /**
     * Allowed AG Grid SSRM aggregation function tokens. Both the request
     * payload and the column registry's {@code defaultAggFunc} normalise
     * to lower-case before comparing, so the set is canonical.
     *
     * <p>PR-0.4z (2026-05) extends the original PR-0.2 set
     * ({@code sum, avg, min, max, count}) with three direct MSSQL
     * aggregate mappings: {@code stddev} → {@code STDEV},
     * {@code stddevp} → {@code STDEVP}, and {@code distinctcount} →
     * {@code COUNT(DISTINCT ...)}. Reports opt in per column via
     * {@code defaultAggFunc} or per request via {@code valueCols.aggFunc}.
     *
     * <p>PR #6a (Codex thread 019e2695): {@code median} joins the
     * whitelist with a different SQL shape — PERCENTILE_CONT is a
     * window function in MSSQL, so {@link #buildGroupedQuery} routes
     * median through an inner-subquery + outer-MAX-collapse path
     * instead of the simple {@code FUNC([field])} render. The simple
     * {@link #renderAggExpression} helper rejects median to make the
     * routing invariant explicit.
     *
     * <p>Remaining roadmap functions: {@code percentile} (PR #6b
     * with registry {@code aggParams} contract) and
     * {@code weightedAvg} (PR-0.4 with value+weight pair semantics).
     */
    private static final Set<String> ALLOWED_AGG_FUNCS = Set.of(
            "sum", "avg", "min", "max", "count",
            "stddev", "stddevp", "distinctcount",
            "median");

    /**
     * Specifies a single value-column aggregation for
     * {@link #buildGroupedQuery(ReportDefinition, YearlySchemaResolver.ResolvedSchemas,
     * List, String, List, Map, List, String, MapSqlParameterSource, int, int)}.
     * The SQL builder emits {@code <func>([field]) AS [field]} so the
     * aggregated column shadows the raw field on the response row, matching
     * AG Grid's convention. {@code distinctcount} renders as
     * {@code COUNT(DISTINCT [field]) AS [field]}.
     *
     * @param field SQL column name (must be in the visible-columns
     *              allow-list).
     * @param func  Aggregation function (sum / avg / min / max / count /
     *              stddev / stddevp / distinctcount).
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
            // Locale.ROOT avoids the Turkish dotless-ı trap on tr_TR
            // hosts — "MEDIAN".toLowerCase() would otherwise drop to
            // "medıan" and miss the whitelist. Mirrors the same
            // discipline applied to ColumnDefinition.defaultAggFunc.
            String normalized = func.trim().toLowerCase(java.util.Locale.ROOT);
            if (!ALLOWED_AGG_FUNCS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "GroupedAggregation func must be one of "
                                + ALLOWED_AGG_FUNCS + ", got: " + func);
            }
            func = normalized;
        }
    }

    /**
     * Render the aggregate SQL expression for a {@link GroupedAggregation}.
     * Token-to-T-SQL mapping:
     * <ul>
     *   <li>{@code sum / avg / min / max / count} → canonical
     *       {@code FUNC([field])}.</li>
     *   <li>{@code stddev} → {@code STDEV([field])} (MSSQL sample
     *       standard deviation — single {@code D}).</li>
     *   <li>{@code stddevp} → {@code STDEVP([field])} (MSSQL population
     *       standard deviation).</li>
     *   <li>{@code distinctcount} → {@code COUNT(DISTINCT [field])}.</li>
     * </ul>
     *
     * <p>The AG Grid SSRM payload uses the double-{@code D} {@code stddev}
     * convention, so the token is normalised at the registry/DTO boundary
     * and the MSSQL spelling is applied only at render time. This keeps
     * the registry vocabulary aligned with the frontend value-column
     * picker while emitting valid T-SQL on the wire.
     *
     * <p>The output never includes an outer {@code AS [alias]} — callers
     * append the alias separately to keep the alias contract uniform with
     * other emitted columns.
     */
    private static String renderAggExpression(GroupedAggregation a) {
        return switch (a.func()) {
            case "distinctcount" -> "COUNT(DISTINCT [" + a.field() + "])";
            case "stddev" -> "STDEV([" + a.field() + "])";
            case "stddevp" -> "STDEVP([" + a.field() + "])";
            // PR #6a (Codex 019e2695 iter-6): median must never travel
            // through the simple-render path. PERCENTILE_CONT is a
            // window function in MSSQL, so calling FUNC([field]) here
            // would emit invalid T-SQL. The buildGroupedQuery median
            // branch handles this case; if a future refactor routes a
            // median aggregation here by accident, fail loudly rather
            // than silently producing "MEDIAN([col])".
            case "median" -> throw new IllegalStateException(
                    "median aggregation must use the buildGroupedQuery "
                            + "PERCENTILE_CONT window path, not renderAggExpression");
            default -> a.func().toUpperCase(java.util.Locale.ROOT)
                    + "([" + a.field() + "])";
        };
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

        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        // PR #6a (Codex thread 019e2695): median requires a different
        // SQL shape — MSSQL exposes PERCENTILE_CONT only as a window
        // function, so we wrap the filtered source in an inner SELECT
        // that emits the window result alongside the raw row, then
        // collapse to one row per group with MAX(__median_<field>) in
        // the outer SELECT. Non-median aggregations stay on the
        // existing single-pass path so byte-for-byte parity is
        // preserved for the simple SUM/AVG/MIN/MAX/COUNT/STDEV cases.
        boolean hasMedian = false;
        for (GroupedAggregation a : sanitized) {
            if ("median".equals(a.func())) {
                hasMedian = true;
                break;
            }
        }

        StringBuilder sql = new StringBuilder();
        if (!hasMedian) {
            sql.append("SELECT [").append(groupColumn).append("]");
            sql.append(", COUNT(*) AS [_rowCount]");
            for (GroupedAggregation a : sanitized) {
                sql.append(", ").append(renderAggExpression(a))
                   .append(" AS [").append(a.field()).append("]");
            }
            sql.append(" FROM ").append(fromResult.sql());
            sql.append(" GROUP BY [").append(groupColumn).append("]");
        } else {
            // Outer projection: groupCol + COUNT(*) + (standard agg | MAX-collapse for median)
            sql.append("SELECT [").append(groupColumn).append("]");
            sql.append(", COUNT(*) AS [_rowCount]");
            for (GroupedAggregation a : sanitized) {
                if ("median".equals(a.func())) {
                    sql.append(", MAX([__median_").append(a.field()).append("])")
                       .append(" AS [").append(a.field()).append("]");
                } else {
                    sql.append(", ").append(renderAggExpression(a))
                       .append(" AS [").append(a.field()).append("]");
                }
            }
            sql.append(" FROM (");
            sql.append("SELECT ").append(selectCols);
            for (GroupedAggregation a : sanitized) {
                if ("median".equals(a.func())) {
                    sql.append(", PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [")
                       .append(a.field()).append("])")
                       .append(" OVER (PARTITION BY [").append(groupColumn).append("])")
                       .append(" AS [__median_").append(a.field()).append("]");
                }
            }
            sql.append(" FROM ").append(fromResult.sql());
            sql.append(") AS _med");
            sql.append(" GROUP BY [").append(groupColumn).append("]");
        }

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

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
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

        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM (");
        sql.append("SELECT [").append(groupColumn).append("]");
        sql.append(" FROM ").append(fromResult.sql());
        sql.append(" GROUP BY [").append(groupColumn).append("]");
        sql.append(") AS _g");

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
    }

    /**
     * Resolve the {@code ORDER BY} clause for a grouped query.
     *
     * <p>Allowed sort fields are the group column and any aggregation
     * alias. Unknown / disallowed entries are skipped silently. If the
     * resulting list is empty, returns an ascending sort on the group
     * column so paging is deterministic.
     *
     * <p>PR #2 hardening (Codex thread 019e2695): when the caller sorts
     * on aggregate aliases only, two buckets sharing the same aggregated
     * value would otherwise sit in arbitrary MSSQL order. With
     * {@code OFFSET / FETCH NEXT} pagination that means an SSRM page
     * window can skip or duplicate a row across navigations. The
     * translator therefore appends {@code [groupColumn] ASC} as a
     * stable tie-breaker whenever the caller did not already include
     * the group column anywhere in {@code sortModel}.
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
        boolean groupColumnIncluded = false;
        for (Map<String, String> entry : sortModel) {
            String colId = entry.get("colId");
            String dir = entry.get("sort");
            if (colId == null || !allowedSortCols.contains(colId)) continue;
            String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
            if (sb.length() > 0) sb.append(", ");
            sb.append("[").append(colId).append("] ").append(direction);
            if (groupColumn.equals(colId)) {
                groupColumnIncluded = true;
            }
        }
        if (sb.length() == 0) {
            // No valid entries — fall back to the deterministic default
            // so paging remains reproducible even when AG Grid sends a
            // sort list referencing only invalid / non-allowed columns.
            return "[" + groupColumn + "] ASC";
        }
        if (!groupColumnIncluded) {
            // Aggregate-only (or non-group-column) sort path. Inject the
            // stable tie-breaker so OFFSET/FETCH pagination across
            // same-aggregate buckets stays deterministic on MSSQL.
            sb.append(", [").append(groupColumn).append("] ASC");
        }
        return sb.toString();
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
    private FromClauseResult buildFromClause(ReportDefinition def,
                                             YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                             String selectCols,
                                             String rlsWhereClause,
                                             MapSqlParameterSource rlsParams,
                                             FilterTranslator.FilterResult filterResult,
                                             MapSqlParameterSource params) {

        boolean isMultiSchema = resolvedSchemas != null && !resolvedSchemas.isSingle();
        List<DegradationWarning> warnings = new ArrayList<>();

        if (!isMultiSchema) {
            // Single schema — original flat query (no subquery overhead)
            YearlySchemaResolver.Branch branch =
                    (resolvedSchemas != null && !resolvedSchemas.branches().isEmpty())
                            ? resolvedSchemas.branches().get(0)
                            : null;
            String schema = branch != null ? branch.transactionSchema() : def.sourceSchema();

            StringBuilder sb = new StringBuilder();
            if (def.hasSourceQuery()) {
                // Custom SQL query — wrap as subquery; render placeholders
                // including {schema} (transaction) and
                // {tenantSetupProcessCatRelation} (Codex 019e0c99 iter-3).
                String resolvedQuery = renderSourceQuery(
                        def.sourceQuery(), branch, schema, def.key(), warnings);
                sb.append("(").append(resolvedQuery).append(") AS _src");
            } else {
                sb.append("[").append(schema).append("].[").append(def.source()).append("] WITH (NOLOCK)");
            }
            sb.append(" WHERE 1=1");
            appendWhereFilters(sb, rlsWhereClause, rlsParams, filterResult, params);
            return new FromClauseResult(sb.toString(), warnings);
        }

        // Multi-schema UNION ALL — wrap in subquery
        StringBuilder union = new StringBuilder();
        union.append("(\n");

        List<YearlySchemaResolver.Branch> branches = resolvedSchemas.branches();
        for (int i = 0; i < branches.size(); i++) {
            YearlySchemaResolver.Branch branch = branches.get(i);
            if (i > 0) {
                union.append("\n  UNION ALL\n");
            }
            union.append("  SELECT ").append(selectCols);
            if (def.hasSourceQuery()) {
                String resolvedQuery = renderSourceQuery(
                        def.sourceQuery(), branch, branch.transactionSchema(),
                        def.key(), warnings);
                union.append(" FROM (").append(resolvedQuery).append(") AS _src");
            } else {
                union.append(" FROM [").append(branch.transactionSchema())
                        .append("].[").append(def.source()).append("] WITH (NOLOCK)");
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

        return new FromClauseResult(union.toString(), warnings);
    }

    /**
     * Render a {@code sourceQuery} template by substituting all known
     * placeholders. Codex 019e0c99 iter-3 §B prescription:
     *
     * <ul>
     *   <li>{@code {schema}} → the branch's transaction schema (yearly
     *       partition, e.g. {@code workcube_mikrolink_2026_35}).</li>
     *   <li>{@code {tenantSetupProcessCatRelation}} → either the per-tenant
     *       master table reference (alias {@code SPC} + NOLOCK hint) when
     *       the lookup is available, or an empty-rowset subquery preserving
     *       the alias contract when the lookup is absent. The placeholder
     *       fragment INCLUDES the alias; templates MUST NOT append
     *       {@code SPC} after the placeholder.</li>
     * </ul>
     *
     * <p>For static reports {@code branch} is the synthetic single-branch
     * with {@code tenantLookupAvailable=false}; the empty-rowset fallback
     * keeps SQL compile-safe even though static reports don't currently use
     * the tenant placeholder family.
     */
    private String renderSourceQuery(String template,
                                     YearlySchemaResolver.Branch branch,
                                     String schemaForLegacyToken,
                                     String reportKey,
                                     List<DegradationWarning> warnings) {
        String result = template.replace("{schema}", schemaForLegacyToken);
        if (result.contains("{tenantSetupProcessCatRelation}")) {
            String fragment = renderTenantSetupProcessCatRelation(branch, reportKey, warnings);
            result = result.replace("{tenantSetupProcessCatRelation}", fragment);
        }
        return result;
    }

    /**
     * Codex 019e0c99 iter-3 §B helper: per-branch render of the
     * {@code SETUP_PROCESS_CAT} relation fragment. The fragment carries
     * alias {@code SPC} and (when available) the NOLOCK hint; callers MUST
     * NOT append an additional alias after the placeholder.
     *
     * <p>Visible-degrade contract: when {@code branch.tenantLookupAvailable()}
     * is {@code false}, an empty-rowset subquery is emitted that preserves
     * the alias and the {@code PROCESS_CAT_ID}/{@code PROCESS_CAT} columns
     * with NULL values. The existing {@code ISNULL(SPC.PROCESS_CAT, 'Diger')}
     * pattern in the SELECT list of the 6 finance reports therefore
     * resolves to {@code 'Diger'} without SQL compile errors. A
     * {@link DegradationWarning} is appended to the warnings list so the
     * controller can surface {@code X-Report-Degraded:tenant_lookup_unavailable}.
     *
     * <p>WITH (NOLOCK) is intentionally NOT applied to the derived-table
     * form (Codex iter-3 explicit guidance: hint only on real tables).
     *
     * <p>Visible at package scope for unit tests only.
     */
    String renderTenantSetupProcessCatRelation(YearlySchemaResolver.Branch branch,
                                               String reportKey,
                                               List<DegradationWarning> warnings) {
        if (branch != null && branch.tenantLookupAvailable()) {
            return "[" + branch.tenantSchema() + "].[" + SETUP_PROCESS_CAT_TABLE
                    + "] SPC WITH (NOLOCK)";
        }
        if (branch != null && warnings != null) {
            warnings.add(DegradationWarning.tenantLookupUnavailable(
                    branch.tenantId(), reportKey, SETUP_PROCESS_CAT_TABLE));
        }
        return "(SELECT CAST(NULL AS int) AS PROCESS_CAT_ID, "
                + "CAST(NULL AS nvarchar(4000)) AS PROCESS_CAT WHERE 1 = 0) SPC";
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
