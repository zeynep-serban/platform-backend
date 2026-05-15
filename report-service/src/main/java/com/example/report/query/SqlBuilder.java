package com.example.report.query;

import com.example.report.registry.PivotValue;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * Hot fix 2026-05-14: {@code @Component} added so
 * {@link com.example.report.workcube.WorkcubeQueryAdapter} (PR #184
 * Adım 11.2b-1) can constructor-inject a SqlBuilder bean. Production
 * report-service context initialization was failing with
 * UnsatisfiedDependencyException because SqlBuilder had no Spring
 * stereotype. Existing {@link QueryEngine#sqlBuilder} field
 * ({@code new SqlBuilder()} direct instantiation) is unaffected — it
 * keeps its private instance; Spring just additionally creates a bean
 * for adapters that want one injected.
 */
@Component
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

    /**
     * PR-0.4b (Codex thread {@code 019e2695}): SQL artifact + ordered pivot
     * alias list produced by {@link #buildPivotedGroupedQuery}. The alias
     * list mirrors AG Grid SSRM's {@code pivotResultFields} contract — the
     * controller surfaces it on the response envelope so the frontend can
     * register secondary columns without re-deriving the alias format from
     * the result rows alone.
     *
     * <p>The list is always immutable (copy on construction) so a future
     * controller-side mutation can't poison the response shape mid-flight.
     */
    public record PivotedBuiltQuery(
            String sql,
            MapSqlParameterSource params,
            List<DegradationWarning> warnings,
            List<String> pivotResultFields,
            List<PivotResultColumn> pivotResultColumns) {

        public PivotedBuiltQuery {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            pivotResultFields = pivotResultFields == null
                    ? List.of()
                    : List.copyOf(pivotResultFields);
            pivotResultColumns = pivotResultColumns == null
                    ? List.of()
                    : List.copyOf(pivotResultColumns);
            // PR-0.4d-be (Codex thread 019e2695): ordering invariant —
            // the two lists must align index-by-index so frontend can
            // index either list with the same row pointer. Catch a
            // mis-built query at construction time rather than letting
            // the response shape drift silently.
            if (!pivotResultColumns.isEmpty()
                    && pivotResultColumns.size() != pivotResultFields.size()) {
                throw new IllegalArgumentException(
                        "PivotedBuiltQuery pivotResultColumns ("
                                + pivotResultColumns.size()
                                + ") must match pivotResultFields ("
                                + pivotResultFields.size() + ")");
            }
            for (int i = 0; i < pivotResultColumns.size(); i++) {
                if (!pivotResultColumns.get(i).field().equals(pivotResultFields.get(i))) {
                    throw new IllegalArgumentException(
                            "PivotedBuiltQuery pivotResultColumns[" + i
                                    + "].field='" + pivotResultColumns.get(i).field()
                                    + "' must equal pivotResultFields[" + i
                                    + "]='" + pivotResultFields.get(i) + "'");
                }
            }
        }

        /**
         * Backward-compat constructor for callers that predate PR-0.4d-be.
         * Defaults {@code pivotResultColumns} to an empty list so older
         * consumers (mock builds, unit tests) keep compiling. The
         * canonical path now always populates the metadata.
         */
        public PivotedBuiltQuery(String sql, MapSqlParameterSource params,
                                  List<DegradationWarning> warnings,
                                  List<String> pivotResultFields) {
            this(sql, params, warnings, pivotResultFields, List.of());
        }
    }

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
     * <p>PR #6a + PR #6b (Codex thread 019e2695): {@code median} and
     * {@code percentilecont} both depend on MSSQL's
     * {@code PERCENTILE_CONT} window function, so {@link #buildGroupedQuery}
     * routes them through an inner-subquery + outer-MAX-collapse path
     * instead of the simple {@code FUNC([field])} render. The simple
     * {@link #renderAggExpression} helper rejects both tokens to make
     * the routing invariant explicit.
     *
     * <p>PR-0.4c (2026-05): {@code weightedavg} accepts a paired
     * weight column via {@code params.weightField}. The rendered SQL
     * is the null-safe ratio {@code SUM(value * weight) / NULLIF(SUM(
     * CASE WHEN value IS NOT NULL AND weight IS NOT NULL THEN weight
     * END), 0)} — rows where either operand is null fall out of both
     * numerator and denominator so the result honours the same
     * "exclude nulls" semantic as MSSQL's plain {@code AVG}. Reports
     * declare the weight column at registry time via
     * {@code defaultAggParams.weightField}; per-request overrides go
     * through {@code valueCols.aggParams.weightField}.
     */
    private static final Set<String> ALLOWED_AGG_FUNCS = Set.of(
            "sum", "avg", "min", "max", "count",
            "stddev", "stddevp", "distinctcount",
            "median", "percentilecont", "weightedavg");

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
     * @param func    Aggregation function token (sum / avg / min / max /
     *                count / stddev / stddevp / distinctcount / median /
     *                percentilecont). {@code median} and {@code percentilecont}
     *                are routed through the inner-subquery PERCENTILE_CONT
     *                window path by {@link #buildGroupedQuery}; the rest
     *                use {@link #renderAggExpression}.
     * @param params  PR #6b: parametric aggregation arguments. Only
     *                {@code percentilecont} consumes them today, with a
     *                required {@code percentile} entry in {@code [0, 1]}.
     */
    public record GroupedAggregation(String field, String func, Map<String, Object> params) {
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
            // PR #6b (Codex 019e2695): params normalization. Empty maps
            // collapse to null so the rest of the pipeline can use a
            // single null-check rather than two. Non-empty maps are
            // copied to an immutable view so a future mutation on the
            // caller side cannot leak into the canonical record.
            if (params != null) {
                params = params.isEmpty() ? null : Map.copyOf(params);
            }
        }

        /**
         * Backward-compatible 2-arg constructor for call sites that
         * predate PR #6b. Defaults {@code params} to {@code null} so
         * existing PR-0.2 / PR #6a code keeps compiling and running
         * without recompilation surprises.
         */
        public GroupedAggregation(String field, String func) {
            this(field, func, null);
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
            // PR-0.4c (2026-05): weighted-average aggregation. The
            // SQL renders the null-safe ratio
            //   SUM(value * weight) / NULLIF(SUM(CASE WHEN value IS
            //     NOT NULL AND weight IS NOT NULL THEN weight END), 0)
            // so the denominator only sums weights for rows where both
            // operands are non-null (matches MSSQL AVG's null-exclusion
            // semantic). NULLIF prevents divide-by-zero — when every
            // row in the bucket has a null value or weight the cell
            // resolves to NULL instead of erroring.
            //
            // The weight column reference is rendered as a bracketed
            // identifier rather than a bind parameter because the
            // controller layer (sanitizeAggParams) already validates
            // weightField against the visible-columns allow-list +
            // ColumnDefinition.type=='number'.
            case "weightedavg" -> "SUM([" + a.field() + "] * [" + weightFieldOf(a) + "])"
                    + " / NULLIF(SUM(CASE WHEN [" + a.field() + "] IS NOT NULL AND ["
                    + weightFieldOf(a) + "] IS NOT NULL THEN [" + weightFieldOf(a)
                    + "] END), 0)";
            // PR #6a (Codex 019e2695 iter-6): median and percentilecont
            // must never travel through the simple-render path.
            // PERCENTILE_CONT is a window function in MSSQL, so calling
            // FUNC([field]) here would emit invalid T-SQL. The
            // buildGroupedQuery percentile branch handles these cases;
            // if a future refactor routes one of these aggregations
            // here by accident, fail loudly rather than silently
            // producing "MEDIAN([col])" or "PERCENTILECONT([col])".
            case "median", "percentilecont" -> throw new IllegalStateException(
                    a.func() + " aggregation must use the buildGroupedQuery "
                            + "PERCENTILE_CONT window path, not renderAggExpression");
            default -> a.func().toUpperCase(java.util.Locale.ROOT)
                    + "([" + a.field() + "])";
        };
    }

    /**
     * PR-0.4c: extract the canonical {@code weightField} for a
     * weighted-average aggregation. The controller layer
     * ({@code sanitizeAggParams}) guarantees the param is present,
     * non-blank, and references a numeric column in the visible-columns
     * allow-list — this helper just hands the validated string back to
     * the SQL renderer without re-walking the map.
     */
    private static String weightFieldOf(GroupedAggregation a) {
        Object raw = a.params() != null ? a.params().get("weightField") : null;
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "weightedavg aggregation requires params.weightField, got: " + raw);
        }
        return s;
    }

    /**
     * PR #6a + PR #6b: identify aggregations that must travel through
     * the PERCENTILE_CONT window subquery path. Used by
     * {@link #buildGroupedQuery} to pick the median/percentile-aware
     * SQL shape; standard aggregates keep the legacy single-pass path.
     */
    private static boolean isPercentileWindowAgg(GroupedAggregation a) {
        return "median".equals(a.func()) || "percentilecont".equals(a.func());
    }

    /**
     * PR #6a + PR #6b: stable internal alias for the
     * {@code PERCENTILE_CONT} window result so the outer
     * {@code MAX(__alias)} collapse stays deterministic. {@code median}
     * keeps its historical {@code __median_<field>} shape for
     * byte-for-byte parity with PR #6a; {@code percentilecont} uses
     * {@code __pctcont_<field>} so a request mixing both aggregates on
     * different fields stays unambiguous.
     */
    private static String internalPctAlias(GroupedAggregation a) {
        if ("median".equals(a.func())) {
            return "__median_" + a.field();
        }
        return "__pctcont_" + a.field();
    }

    /**
     * PR #6b: render the {@code PERCENTILE_CONT} percentile rank as a
     * validated numeric literal (e.g. {@code 0.9}) rather than a SQL
     * bind parameter.
     *
     * <p>MSSQL's {@code PERCENTILE_CONT} signature accepts a numeric
     * literal in the parentheses; named parameters are syntactically
     * accepted by JDBC but the risk surface is unnecessary when the
     * value is already type-validated and range-checked upstream. The
     * controller layer ({@code sanitizeAggregations}) enforces
     * {@code value instanceof Number} and {@code 0 <= p <= 1} before
     * the aggregation reaches the SQL builder, so the cast here is
     * safe by contract.
     *
     * <p>{@code BigDecimal.valueOf(...).stripTrailingZeros().toPlainString()}
     * keeps the rendered literal stable across {@code Double.toString}
     * locale or scientific-notation surprises (e.g. {@code 1.0E-1}).
     * Median's fixed {@code 0.5} short-circuits through the same path.
     */
    private static String percentileLiteral(GroupedAggregation a) {
        if ("median".equals(a.func())) {
            return "0.5";
        }
        Object raw = a.params() != null ? a.params().get("percentile") : null;
        if (!(raw instanceof Number n)) {
            throw new IllegalStateException(
                    "percentilecont aggregation requires params.percentile "
                            + "to be a Number, got: " + raw);
        }
        double p = n.doubleValue();
        if (!Double.isFinite(p) || p < 0.0 || p > 1.0) {
            throw new IllegalStateException(
                    "percentilecont aggregation requires 0 <= percentile <= 1, got: " + p);
        }
        return java.math.BigDecimal.valueOf(p)
                .stripTrailingZeros()
                .toPlainString();
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
            // PR-0.4c iter-2 (Codex 019e2acc absorb): weightedavg's
            // weight column must also be carried through the
            // FROM-clause projection so the outer SUM(value * weight)
            // can resolve it. Otherwise multi-schema UNION ALL
            // (yearly) and percentile-wrapper inner SELECTs would
            // emit "SELECT [category], [price]" while the outer
            // aggregate references [qty] → MSSQL "Invalid column"
            // error at execute time. Defence-in-depth: weight column
            // visibility + distinctness re-validated here so a
            // future caller that bypasses the controller still fails
            // closed with a clear error before the SQL builder
            // emits broken DDL.
            if ("weightedavg".equals(a.func())) {
                Object weightRef = a.params() != null ? a.params().get("weightField") : null;
                if (!(weightRef instanceof String weight) || weight.isBlank()) {
                    throw new IllegalArgumentException(
                            "weightedavg aggregation requires params.weightField on field '"
                                    + a.field() + "'");
                }
                if (!allowedCols.contains(weight)) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must be one of the visible "
                                    + "columns, got: " + weight);
                }
                if (weight.equals(a.field())) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must differ from value field, "
                                    + "got: " + weight + " for both on '" + a.field() + "'");
                }
                projected.add(weight);
            }
            sanitized.add(a);
        }

        String selectCols = projected.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        // PR #6a + PR #6b (Codex thread 019e2695): median and
        // percentilecont both depend on MSSQL's PERCENTILE_CONT, which
        // is a window function. Either aggregation in the request
        // diverts the query to a wrapper shape — inner SELECT emits
        // the window result with an internal alias, outer SELECT
        // collapses one row per group via MAX(__alias). Standard
        // aggregations (sum/avg/min/max/count/stddev/...) stay on the
        // existing single-pass path so the byte-for-byte parity is
        // preserved for the legacy SUM/AVG/MIN/MAX/COUNT cases.
        boolean hasPercentileWindow = false;
        for (GroupedAggregation a : sanitized) {
            if (isPercentileWindowAgg(a)) {
                hasPercentileWindow = true;
                break;
            }
        }

        StringBuilder sql = new StringBuilder();
        if (!hasPercentileWindow) {
            sql.append("SELECT [").append(groupColumn).append("]");
            sql.append(", COUNT(*) AS [_rowCount]");
            for (GroupedAggregation a : sanitized) {
                sql.append(", ").append(renderAggExpression(a))
                   .append(" AS [").append(a.field()).append("]");
            }
            sql.append(" FROM ").append(fromResult.sql());
            sql.append(" GROUP BY [").append(groupColumn).append("]");
        } else {
            // Outer projection: groupCol + COUNT(*) + (standard agg |
            // MAX-collapse for median/percentilecont).
            sql.append("SELECT [").append(groupColumn).append("]");
            sql.append(", COUNT(*) AS [_rowCount]");
            for (GroupedAggregation a : sanitized) {
                if (isPercentileWindowAgg(a)) {
                    sql.append(", MAX([").append(internalPctAlias(a)).append("])")
                       .append(" AS [").append(a.field()).append("]");
                } else {
                    sql.append(", ").append(renderAggExpression(a))
                       .append(" AS [").append(a.field()).append("]");
                }
            }
            sql.append(" FROM (");
            sql.append("SELECT ").append(selectCols);
            for (GroupedAggregation a : sanitized) {
                if (isPercentileWindowAgg(a)) {
                    sql.append(", PERCENTILE_CONT(")
                       .append(percentileLiteral(a))
                       .append(") WITHIN GROUP (ORDER BY [")
                       .append(a.field()).append("])")
                       .append(" OVER (PARTITION BY [").append(groupColumn).append("])")
                       .append(" AS [").append(internalPctAlias(a)).append("]");
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
     * PR-0.5a (2026-05-15, Codex thread {@code 019e2c61}): non-pivot
     * grouped root-level grand total. Emits one row of aggregations
     * over the entire RLS+filter-narrowed source set — no
     * {@code GROUP BY}, no {@code OFFSET/FETCH}. The controller layer
     * gates this query on {@code currentLevel == 0 && !pivotMode &&
     * !valueCols.isEmpty()} so the SSRM frontend receives the global
     * row only on the root request; expanded child stores keep their
     * bucket-level aggregations as before.
     *
     * <p>Aggregate semantics match the grouped path:
     * <ul>
     *   <li>{@code sum, avg, min, max, count, distinctcount, stddev,
     *       stddevp}: native MSSQL aggregate over the full rowset.</li>
     *   <li>{@code median, percentilecont}: PERCENTILE_CONT window
     *       function partitioned over the whole set with an outer
     *       MAX collapse (same wrapper {@link #buildGroupedQuery}
     *       uses for the bucket version).</li>
     *   <li>{@code weightedavg}: null-safe ratio
     *       {@code SUM(value * weight) / NULLIF(SUM(CASE WHEN
     *       value IS NOT NULL AND weight IS NOT NULL THEN weight
     *       END), 0)} — weight column projected through the FROM
     *       clause (PR-0.4c projection contract).</li>
     * </ul>
     *
     * <p>The result is a single-row {@code BuiltQuery}; the calling
     * QueryEngine materialises it as a {@code Map<String, Object>}
     * keyed by the aggregation alias (same field naming as the
     * grouped path so AG Grid's {@code pinnedBottomRowData} can be
     * rendered with the existing {@code colDef.field} bindings).
     */
    public BuiltQuery buildGrandTotalQuery(
            ReportDefinition def,
            YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
            List<String> visibleColumns,
            List<GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            String rlsWhereClause,
            MapSqlParameterSource rlsParams) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        if (aggregations == null || aggregations.isEmpty()) {
            throw new IllegalArgumentException(
                    "buildGrandTotalQuery requires at least one aggregation");
        }

        // Sanitize aggregations — same defensive filter as the
        // grouped path: drop entries that target non-visible columns
        // so a malicious payload can't surface hidden columns through
        // the grand-total envelope.
        List<GroupedAggregation> sanitized = new java.util.ArrayList<>();
        Set<String> projected = new java.util.LinkedHashSet<>();
        for (GroupedAggregation a : aggregations) {
            if (!allowedCols.contains(a.field())) continue;
            projected.add(a.field());
            // PR-0.4c: weightedavg requires the weight column in the
            // FROM-clause projection so the outer SUM can resolve it.
            // Validate visibility + distinctness defensively (controller
            // already enforces this in sanitizeAggregations, but the
            // grand-total path runs through the same builder logic).
            if ("weightedavg".equals(a.func())) {
                Object weightRef = a.params() != null ? a.params().get("weightField") : null;
                if (!(weightRef instanceof String weight) || weight.isBlank()) {
                    throw new IllegalArgumentException(
                            "weightedavg aggregation requires params.weightField on field '"
                                    + a.field() + "'");
                }
                if (!allowedCols.contains(weight)) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must be one of the visible "
                                    + "columns, got: " + weight);
                }
                if (weight.equals(a.field())) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must differ from value field, "
                                    + "got: " + weight + " for both on '" + a.field() + "'");
                }
                projected.add(weight);
            }
            sanitized.add(a);
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "buildGrandTotalQuery: every aggregation referenced a non-visible "
                            + "column; nothing to emit");
        }

        String selectCols = projected.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult =
                filterTranslator.translate(agGridFilter, allowedCols);
        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas,
                selectCols, rlsWhereClause, rlsParams, filterResult, params);

        // Check whether the request mixes median/percentilecont with
        // standard aggregations — same wrapper shape as buildGroupedQuery.
        boolean hasPercentileWindow = false;
        for (GroupedAggregation a : sanitized) {
            if (isPercentileWindowAgg(a)) {
                hasPercentileWindow = true;
                break;
            }
        }

        StringBuilder sql = new StringBuilder();
        if (!hasPercentileWindow) {
            // Plain aggregate: SELECT SUM([amount]) AS [amount], …
            sql.append("SELECT ");
            for (int i = 0; i < sanitized.size(); i++) {
                if (i > 0) sql.append(", ");
                GroupedAggregation a = sanitized.get(i);
                sql.append(renderAggExpression(a))
                        .append(" AS [").append(a.field()).append("]");
            }
            sql.append(" FROM ").append(fromResult.sql());
        } else {
            // Mixed agg with percentile window: wrap inner SELECT with
            // PERCENTILE_CONT OVER () (no PARTITION BY — global).
            // Outer projection collapses with MAX so the single output
            // row stays deterministic.
            sql.append("SELECT ");
            for (int i = 0; i < sanitized.size(); i++) {
                if (i > 0) sql.append(", ");
                GroupedAggregation a = sanitized.get(i);
                if (isPercentileWindowAgg(a)) {
                    sql.append("MAX([").append(internalPctAlias(a)).append("])")
                            .append(" AS [").append(a.field()).append("]");
                } else {
                    sql.append(renderAggExpression(a))
                            .append(" AS [").append(a.field()).append("]");
                }
            }
            sql.append(" FROM (");
            sql.append("SELECT ").append(selectCols);
            for (GroupedAggregation a : sanitized) {
                if (isPercentileWindowAgg(a)) {
                    sql.append(", PERCENTILE_CONT(")
                            .append(percentileLiteral(a))
                            .append(") WITHIN GROUP (ORDER BY [")
                            .append(a.field()).append("])")
                            // No PARTITION BY → window over the whole set.
                            .append(" OVER ()")
                            .append(" AS [").append(internalPctAlias(a)).append("]");
                }
            }
            sql.append(" FROM ").append(fromResult.sql());
            sql.append(") AS _gt_inner");
        }

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
    }

    // ── Export queries (PR-0.5b grouped + pivoted export) ──────────────

    /**
     * PR-0.5b (Codex thread 019e2cd7): non-pivot multi-level grouped
     * export. Emits leaf buckets (the bottom of the SSRM expansion
     * tree) as a flat table, capped at {@code maxRows} via
     * {@code SELECT TOP(:_maxRows)} — no {@code OFFSET/FETCH}.
     *
     * <p>Multi-level scope: every column in {@code groupColumns}
     * participates in {@code GROUP BY}; the export ships one row per
     * unique combination of group keys. This is the "leaf bucket
     * table" semantic Codex 019e2cd7 §4 explicitly approved for
     * PR-0.5b. UI expanded/collapsed tree snapshots are NOT replicated
     * — that's a {@code GROUPING SETS}/{@code ROLLUP} story for a
     * future PR.
     *
     * <p>Aggregate semantics mirror {@link #buildGroupedQuery}:
     * <ul>
     *   <li>{@code sum/avg/min/max/count/distinctcount/stddev/stddevp}
     *       → native MSSQL aggregate.</li>
     *   <li>{@code median/percentilecont} → PERCENTILE_CONT WITHIN
     *       GROUP OVER (PARTITION BY group keys) + outer MAX collapse
     *       (same wrapper as the live query).</li>
     *   <li>{@code weightedavg} → null-safe ratio with weight column
     *       projected through the FROM clause (PR-0.4c contract).</li>
     * </ul>
     *
     * <p>The {@code COUNT(*) AS [_rowCount]} column is included so the
     * exported file matches what SSRM shows on screen.
     */
    public BuiltQuery buildGroupedExportQuery(
            ReportDefinition def,
            YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
            List<String> visibleColumns,
            List<String> groupColumns,
            List<GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            List<Map<String, String>> sortModel,
            String rlsWhereClause,
            MapSqlParameterSource rlsParams,
            int maxRows) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        if (groupColumns == null || groupColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "buildGroupedExportQuery requires at least one group column");
        }
        for (String gc : groupColumns) {
            if (gc == null || !allowedCols.contains(gc)) {
                throw new IllegalArgumentException(
                        "groupColumns must be a subset of the visible columns, got: " + gc);
            }
        }

        Set<String> groupColumnSet = new java.util.LinkedHashSet<>(groupColumns);
        // Project group columns + aggregation targets through the FROM
        // subquery so the outer GROUP BY/aggregate can resolve them.
        Set<String> projected = new java.util.LinkedHashSet<>(groupColumnSet);
        List<GroupedAggregation> sanitized = new java.util.ArrayList<>();
        for (GroupedAggregation a : aggregations != null ? aggregations : List.<GroupedAggregation>of()) {
            if (!allowedCols.contains(a.field())) continue;
            if (groupColumnSet.contains(a.field())) continue;
            projected.add(a.field());
            // weightedavg weight projection (PR-0.4c contract) — same
            // defensive validation as the live grouped query path.
            if ("weightedavg".equals(a.func())) {
                Object weightRef = a.params() != null ? a.params().get("weightField") : null;
                if (!(weightRef instanceof String weight) || weight.isBlank()) {
                    throw new IllegalArgumentException(
                            "weightedavg aggregation requires params.weightField on field '"
                                    + a.field() + "'");
                }
                if (!allowedCols.contains(weight)) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must be one of the visible "
                                    + "columns, got: " + weight);
                }
                if (weight.equals(a.field())) {
                    throw new IllegalArgumentException(
                            "weightedavg params.weightField must differ from value field, "
                                    + "got: " + weight + " for both on '" + a.field() + "'");
                }
                projected.add(weight);
            }
            sanitized.add(a);
        }

        String selectCols = projected.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));
        String groupByList = groupColumnSet.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));
        String partitionByList = groupByList; // window PARTITION BY mirrors GROUP BY

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult =
                filterTranslator.translate(agGridFilter, allowedCols);
        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        boolean hasPercentileWindow = false;
        for (GroupedAggregation a : sanitized) {
            if (isPercentileWindowAgg(a)) {
                hasPercentileWindow = true;
                break;
            }
        }

        StringBuilder sql = new StringBuilder();
        // SELECT TOP applies the export cap deterministically; no OFFSET/FETCH.
        sql.append("SELECT TOP(:_maxRows) ");
        sql.append(groupColumnSet.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", ")));
        sql.append(", COUNT(*) AS [_rowCount]");
        for (GroupedAggregation a : sanitized) {
            if (hasPercentileWindow && isPercentileWindowAgg(a)) {
                sql.append(", MAX([").append(internalPctAlias(a)).append("])")
                   .append(" AS [").append(a.field()).append("]");
            } else {
                sql.append(", ").append(renderAggExpression(a))
                   .append(" AS [").append(a.field()).append("]");
            }
        }
        if (!hasPercentileWindow) {
            sql.append(" FROM ").append(fromResult.sql());
        } else {
            // Inner SELECT projects raw columns + PERCENTILE_CONT window
            // partitioned by every group column; outer GROUP BY collapses
            // each bucket via MAX.
            sql.append(" FROM (SELECT ").append(selectCols);
            for (GroupedAggregation a : sanitized) {
                if (isPercentileWindowAgg(a)) {
                    sql.append(", PERCENTILE_CONT(")
                       .append(percentileLiteral(a))
                       .append(") WITHIN GROUP (ORDER BY [")
                       .append(a.field()).append("])")
                       .append(" OVER (PARTITION BY ").append(partitionByList).append(")")
                       .append(" AS [").append(internalPctAlias(a)).append("]");
                }
            }
            sql.append(" FROM ").append(fromResult.sql());
            sql.append(") AS _med");
        }
        sql.append(" GROUP BY ").append(groupByList);
        params.addValue("_maxRows", maxRows);

        // Deterministic ORDER BY for reproducible exports: honour the
        // caller's sortModel (allow list = group columns + agg aliases),
        // then append every group column ASC chain so two runs over the
        // same input produce byte-identical output.
        Set<String> allowedSortCols = new java.util.HashSet<>(groupColumnSet);
        for (GroupedAggregation a : sanitized) allowedSortCols.add(a.field());
        StringBuilder orderBy = new StringBuilder();
        Set<String> seenSortCols = new java.util.HashSet<>();
        if (sortModel != null) {
            for (Map<String, String> entry : sortModel) {
                String colId = entry.get("colId");
                String dir = entry.get("sort");
                if (colId == null || !allowedSortCols.contains(colId)) continue;
                if (!seenSortCols.add(colId)) continue;
                String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
                if (orderBy.length() > 0) orderBy.append(", ");
                orderBy.append("[").append(colId).append("] ").append(direction);
            }
        }
        for (String gc : groupColumnSet) {
            if (!seenSortCols.contains(gc)) {
                if (orderBy.length() > 0) orderBy.append(", ");
                orderBy.append("[").append(gc).append("] ASC");
                seenSortCols.add(gc);
            }
        }
        sql.append(" ORDER BY ").append(orderBy);

        return new BuiltQuery(sql.toString(), params, fromResult.warnings());
    }

    /**
     * PR-0.5b (Codex thread 019e2cd7): single-level pivot export. Emits
     * the same {@code groupColumn + COUNT(*) + pivoted aggregations}
     * shape as {@link #buildPivotedGroupedQuery} but pagination is
     * removed and the row cap is enforced via {@code SELECT TOP}.
     *
     * <p>The returned {@link PivotedBuiltQuery} carries the
     * {@code pivotResultColumns} metadata so the controller layer can
     * build user-facing export headers
     * ({@code <pivotLabel> / <AGG>(<valueField>)}) without re-deriving
     * label/agg/value from the SQL alias string.
     */
    public PivotedBuiltQuery buildPivotedGroupedExportQuery(
            ReportDefinition def,
            YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
            List<String> visibleColumns,
            String groupColumn,
            String pivotColumn,
            List<PivotValue> pivotValues,
            List<GroupedAggregation> aggregations,
            Map<String, Object> agGridFilter,
            List<Map<String, String>> sortModel,
            String rlsWhereClause,
            MapSqlParameterSource rlsParams,
            int maxRows) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        if (groupColumn == null || !allowedCols.contains(groupColumn)) {
            throw new IllegalArgumentException(
                    "groupColumn must be one of the visible columns, got: " + groupColumn);
        }
        if (pivotColumn == null || !allowedCols.contains(pivotColumn)) {
            throw new IllegalArgumentException(
                    "pivotColumn must be one of the visible columns, got: " + pivotColumn);
        }
        if (pivotColumn.equals(groupColumn)) {
            throw new IllegalArgumentException(
                    "pivotColumn must differ from groupColumn (got "
                            + pivotColumn + " for both)");
        }
        if (pivotValues == null || pivotValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "pivotValues must be non-empty for a pivot export query");
        }

        List<GroupedAggregation> sanitized = new java.util.ArrayList<>();
        Set<String> projectedFields = new java.util.LinkedHashSet<>();
        projectedFields.add(groupColumn);
        projectedFields.add(pivotColumn);
        for (GroupedAggregation a : aggregations != null
                ? aggregations
                : List.<GroupedAggregation>of()) {
            if (!allowedCols.contains(a.field())) continue;
            if (a.field().equals(groupColumn) || a.field().equals(pivotColumn)) {
                continue;
            }
            if (!ALLOWED_PIVOT_AGG_FUNCS.contains(a.func())) {
                throw new IllegalArgumentException(
                        "Aggregation func '" + a.func() + "' is not supported "
                                + "inside a pivot query (allowed: "
                                + ALLOWED_PIVOT_AGG_FUNCS + ")");
            }
            projectedFields.add(a.field());
            sanitized.add(a);
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Pivot export query requires at least one valid aggregation "
                            + "outside the group/pivot columns");
        }
        long totalOutputColumns = (long) pivotValues.size() * sanitized.size();
        if (totalOutputColumns > MAX_PIVOT_OUTPUT_COLUMNS) {
            throw new IllegalArgumentException(
                    "Pivot output column budget exceeded: pivotValues("
                            + pivotValues.size() + ") * valueCols("
                            + sanitized.size() + ") = " + totalOutputColumns
                            + " > " + MAX_PIVOT_OUTPUT_COLUMNS);
        }

        String selectCols = projectedFields.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult =
                filterTranslator.translate(agGridFilter, allowedCols);
        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas,
                selectCols, rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TOP(:_maxRows) [").append(groupColumn).append("]");
        sql.append(", COUNT(*) AS [_rowCount]");

        List<String> pivotResultFields = new ArrayList<>();
        List<PivotResultColumn> pivotResultColumns = new ArrayList<>();
        Set<String> seenAliases = new java.util.HashSet<>();
        for (int pi = 0; pi < pivotValues.size(); pi++) {
            PivotValue pv = pivotValues.get(pi);
            String paramName = "_pivot_" + pi;
            params.addValue(paramName, pv.value());
            for (GroupedAggregation a : sanitized) {
                String alias = pivotAlias(pivotColumn, pv.value(),
                        a.func(), a.field());
                if (!seenAliases.add(alias)) {
                    throw new IllegalArgumentException(
                            "Pivot alias collision detected: '" + alias
                                    + "' — two registry pivotValues sanitise to "
                                    + "the same identifier (raw value '"
                                    + pv.value() + "'). Rename one entry so the "
                                    + "alphanumeric+underscore reduction stays "
                                    + "injective.");
                }
                pivotResultFields.add(alias);
                pivotResultColumns.add(new PivotResultColumn(
                        alias,
                        pivotColumn,
                        pv.value(),
                        pv.label(),
                        a.func(),
                        a.field()));
                sql.append(", ")
                        .append(renderPivotAggExpression(a, pivotColumn, paramName))
                        .append(" AS [").append(alias).append("]");
            }
        }

        sql.append(" FROM ").append(fromResult.sql());
        sql.append(" GROUP BY [").append(groupColumn).append("]");

        String orderBy = translatePivotedSort(sortModel, groupColumn,
                pivotResultFields);
        sql.append(" ORDER BY ").append(orderBy);
        params.addValue("_maxRows", maxRows);

        return new PivotedBuiltQuery(sql.toString(), params,
                fromResult.warnings(), pivotResultFields, pivotResultColumns);
    }

    // ── Pivot queries (PR-0.4b single-level pivot) ──────────────────────

    /**
     * PR-0.4b aggregate funcs that are allowed inside a pivot {@code CASE
     * WHEN} expression. Subset of {@link #ALLOWED_AGG_FUNCS}. The
     * percentile family ({@code median}, {@code percentilecont}) is
     * intentionally excluded — both depend on MSSQL's
     * {@code PERCENTILE_CONT} window function which cannot be re-emitted
     * one-per-pivot-bucket without a more elaborate wrapping shape; that
     * work is deferred to a future PR.
     */
    private static final Set<String> ALLOWED_PIVOT_AGG_FUNCS = Set.of(
            "sum", "avg", "min", "max", "count",
            "stddev", "stddevp", "distinctcount");

    /**
     * PR-0.4b (Codex thread {@code 019e2695}): controller-side cap on the
     * total number of pivot-derived response columns. With one pivot
     * column the materialised count is {@code pivotValues * valueCols};
     * 8 × 4 = 32 is the upper ceiling AG Grid can render without
     * destabilising the secondary-column rendering budget.
     */
    public static final int MAX_PIVOT_OUTPUT_COLUMNS = 32;

    /**
     * Render the pivot-bucket aggregate expression for a single
     * {@link GroupedAggregation} + {@link PivotValue} pair.
     *
     * <p>Agg-specific null semantics (Codex Q5 verdict):
     * <ul>
     *   <li>{@code sum} → {@code SUM(CASE WHEN [pivot] = :p THEN [field] ELSE 0 END)}.
     *       Finance pivots want a zero-rather-than-NULL cell when the
     *       bucket is empty, matching every prior MSSQL pivot the
     *       Workcube reports ship today.</li>
     *   <li>{@code avg} → {@code AVG(CASE WHEN [pivot] = :p THEN [field] END)}.
     *       The {@code ELSE 0} branch would otherwise drag every
     *       out-of-bucket row into the denominator, biasing the
     *       average towards zero.</li>
     *   <li>{@code min}, {@code max} → {@code MIN/MAX(CASE WHEN ... END)};
     *       same null-pass-through rationale as AVG.</li>
     *   <li>{@code count} → {@code COUNT(CASE WHEN ... THEN [field] END)}.
     *       Out-of-bucket rows produce NULL and {@code COUNT(NULL)} omits
     *       them automatically, matching the legacy {@code COUNT([field])}
     *       semantic (non-null count).</li>
     *   <li>{@code distinctcount} → {@code COUNT(DISTINCT CASE WHEN ... END)}.</li>
     *   <li>{@code stddev}, {@code stddevp} → wrap MSSQL's
     *       {@code STDEV}/{@code STDEVP} aggregates the same way as AVG.</li>
     * </ul>
     *
     * <p>The pivot value comparison is bound as the named parameter
     * referenced by {@code paramName} so the predicate never carries a
     * raw SQL literal (NVARCHAR / collation / N-prefix concerns all
     * stay JDBC-owned).
     */
    private static String renderPivotAggExpression(
            GroupedAggregation a, String pivotField, String paramName) {
        String field = a.field();
        String predicate = "[" + pivotField + "] = :" + paramName;
        return switch (a.func()) {
            case "sum" -> "SUM(CASE WHEN " + predicate
                    + " THEN [" + field + "] ELSE 0 END)";
            case "avg" -> "AVG(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "min" -> "MIN(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "max" -> "MAX(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "count" -> "COUNT(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "distinctcount" -> "COUNT(DISTINCT CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "stddev" -> "STDEV(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            case "stddevp" -> "STDEVP(CASE WHEN " + predicate
                    + " THEN [" + field + "] END)";
            default -> throw new IllegalStateException(
                    a.func() + " aggregation is not supported inside a pivot "
                            + "CASE WHEN expression");
        };
    }

    /**
     * PR-0.4b (Codex thread {@code 019e2695}): deterministic SQL alias for
     * a single (pivotField, pivotValue, aggFunc, valueField) tuple. AG
     * Grid SSRM addresses pivot result fields by this identifier on both
     * server-mode and client-mode, so the format must stay stable across
     * the request lifecycle.
     *
     * <p>Format: {@code pvt__<pivotField>__<sanitizedValueKey>__<aggFunc>__<valueField>}
     *
     * <p>The pivot value key is sanitised through {@link #sanitizeAliasToken}
     * so registry-supplied labels containing spaces, slashes, Turkish
     * characters, or other identifier-hostile codepoints can't break the
     * SQL alias contract. The pivot field, agg func and value field are
     * already constrained to allowlisted column names / function tokens
     * upstream, so they ride through unchanged.
     */
    public static String pivotAlias(String pivotField, String pivotValueKey,
                                     String aggFunc, String valueField) {
        return "pvt__" + pivotField
                + "__" + sanitizeAliasToken(pivotValueKey)
                + "__" + aggFunc
                + "__" + valueField;
    }

    /**
     * Map any pivot value key to a deterministic, identifier-friendly
     * token so the materialised SQL alias is reproducible no matter
     * what unicode codepoints the registry carries. Non-alphanumeric
     * codepoints collapse into {@code _} and consecutive underscores
     * fold into one; pure-underscore tokens fall back to a stable
     * hash so two different registry values can't share the same
     * sanitised alias suffix.
     */
    private static String sanitizeAliasToken(String token) {
        if (token == null || token.isBlank()) {
            return "_blank";
        }
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            }
        }
        // Trim trailing underscore so two adjacent tokens don't collapse
        // into an ambiguous `pvt__field__value___sum__amount`.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (sb.length() == 0) {
            // Pure-non-alphanumeric token (e.g. punctuation-only label).
            // Use the original token's hash to keep the alias injective:
            // two different sanitised-to-empty registry values must not
            // resolve to the same SQL alias.
            return "h" + Integer.toHexString(token.hashCode() & 0x7fffffff);
        }
        return sb.toString();
    }

    /**
     * Build the SSRM single-level pivot query (PR-0.4b). SQL shape:
     * <pre>{@code
     * SELECT [groupColumn],
     *        COUNT(*) AS [_rowCount],
     *        SUM(CASE WHEN [pivot] = :pv_0 THEN [v] ELSE 0 END) AS [pvt__pivot__a__sum__v],
     *        SUM(CASE WHEN [pivot] = :pv_1 THEN [v] ELSE 0 END) AS [pvt__pivot__b__sum__v]
     *   FROM <fromClause>
     *  GROUP BY [groupColumn]
     *  ORDER BY [groupColumn] ASC
     *  OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY;
     * }</pre>
     *
     * <p>Caller contract:
     * <ul>
     *   <li>{@code groupColumn} must be in {@code visibleColumns}.</li>
     *   <li>{@code pivotColumn} must be in {@code visibleColumns} and
     *       distinct from {@code groupColumn}.</li>
     *   <li>{@code pivotValues} must be non-empty and ≤
     *       {@link com.example.report.registry.ColumnDefinition#MAX_PIVOT_VALUES}.
     *       Combined cap with {@code valueCols}:
     *       {@code pivotValues.size() * aggregations.size()
     *       <= MAX_PIVOT_OUTPUT_COLUMNS}.</li>
     *   <li>Each {@link GroupedAggregation#field()} must be in
     *       {@code visibleColumns}; aggregations targeting the group or
     *       pivot column are dropped (tautological).</li>
     *   <li>Agg funcs must be in {@link #ALLOWED_PIVOT_AGG_FUNCS};
     *       {@code median} / {@code percentilecont} are reserved for a
     *       future PR.</li>
     * </ul>
     *
     * <p>RLS, filter and sort handling mirror {@link #buildGroupedQuery}.
     * Pivot value bindings use the named parameter prefix {@code _pivot_<n>}
     * to avoid colliding with the filter translator's prefixes.
     */
    public PivotedBuiltQuery buildPivotedGroupedQuery(
            ReportDefinition def,
            YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
            List<String> visibleColumns,
            String groupColumn,
            String pivotColumn,
            List<PivotValue> pivotValues,
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
        if (pivotColumn == null || !allowedCols.contains(pivotColumn)) {
            throw new IllegalArgumentException(
                    "pivotColumn must be one of the visible columns, got: " + pivotColumn);
        }
        if (pivotColumn.equals(groupColumn)) {
            throw new IllegalArgumentException(
                    "pivotColumn must differ from groupColumn (got "
                            + pivotColumn + " for both)");
        }
        if (pivotValues == null || pivotValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "pivotValues must be non-empty for a pivot query");
        }

        // Drop aggregations that target the group or pivot column
        // (tautological) and any field outside the visibility allow-list.
        List<GroupedAggregation> sanitized = new java.util.ArrayList<>();
        Set<String> projectedFields = new java.util.LinkedHashSet<>();
        projectedFields.add(groupColumn);
        projectedFields.add(pivotColumn);
        for (GroupedAggregation a : aggregations != null
                ? aggregations
                : List.<GroupedAggregation>of()) {
            if (!allowedCols.contains(a.field())) continue;
            if (a.field().equals(groupColumn) || a.field().equals(pivotColumn)) {
                continue;
            }
            if (!ALLOWED_PIVOT_AGG_FUNCS.contains(a.func())) {
                throw new IllegalArgumentException(
                        "Aggregation func '" + a.func() + "' is not supported "
                                + "inside a pivot query (allowed: "
                                + ALLOWED_PIVOT_AGG_FUNCS + ")");
            }
            projectedFields.add(a.field());
            sanitized.add(a);
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Pivot query requires at least one valid aggregation "
                            + "outside the group/pivot columns");
        }
        long totalOutputColumns = (long) pivotValues.size() * sanitized.size();
        if (totalOutputColumns > MAX_PIVOT_OUTPUT_COLUMNS) {
            throw new IllegalArgumentException(
                    "Pivot output column budget exceeded: pivotValues("
                            + pivotValues.size() + ") * valueCols("
                            + sanitized.size() + ") = " + totalOutputColumns
                            + " > " + MAX_PIVOT_OUTPUT_COLUMNS);
        }

        String selectCols = projectedFields.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult =
                filterTranslator.translate(agGridFilter, allowedCols);
        FromClauseResult fromResult = buildFromClause(def, resolvedSchemas,
                selectCols, rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT [").append(groupColumn).append("]");
        sql.append(", COUNT(*) AS [_rowCount]");

        List<String> pivotResultFields = new ArrayList<>();
        List<PivotResultColumn> pivotResultColumns = new ArrayList<>();
        // PR-0.4b post-spec hardening (Codex 019e2695 iter-2 P1 absorb):
        // alias sanitisation maps non-identifier codepoints onto `_`, so
        // two distinct registry values like `A-B` and `A/B` would collide
        // onto the same `A_B` alias and produce duplicate response
        // columns AG Grid cannot disambiguate. Collect generated aliases
        // and trip a structured exception the moment a collision is
        // detected — the controller layer surfaces it as
        // `INVALID_PIVOT_REQUEST` so registry maintainers can rename
        // the offending bucket rather than guess why a column went
        // missing on the frontend.
        Set<String> seenAliases = new java.util.HashSet<>();
        // Outer iteration: pivot value first, then aggregation. AG Grid's
        // pivotResultFields contract puts the value buckets adjacent so
        // column groups read naturally left-to-right.
        for (int pi = 0; pi < pivotValues.size(); pi++) {
            PivotValue pv = pivotValues.get(pi);
            String paramName = "_pivot_" + pi;
            params.addValue(paramName, pv.value());
            for (GroupedAggregation a : sanitized) {
                String alias = pivotAlias(pivotColumn, pv.value(),
                        a.func(), a.field());
                if (!seenAliases.add(alias)) {
                    throw new IllegalArgumentException(
                            "Pivot alias collision detected: '" + alias
                                    + "' — two registry pivotValues sanitise to "
                                    + "the same identifier (raw value '"
                                    + pv.value() + "'). Rename one entry so the "
                                    + "alphanumeric+underscore reduction stays "
                                    + "injective (`A-B` and `A/B` both collapse "
                                    + "to `A_B`).");
                }
                pivotResultFields.add(alias);
                // PR-0.4d-be (Codex 019e2695): emit the alias-aligned
                // metadata record so the frontend can build the secondary
                // column header without re-deriving label/agg/value from
                // the SQL alias string. Order matches pivotResultFields
                // index-by-index (PivotedBuiltQuery canonical asserts).
                pivotResultColumns.add(new PivotResultColumn(
                        alias,
                        pivotColumn,
                        pv.value(),
                        pv.label(),
                        a.func(),
                        a.field()));
                sql.append(", ")
                        .append(renderPivotAggExpression(a, pivotColumn, paramName))
                        .append(" AS [").append(alias).append("]");
            }
        }

        sql.append(" FROM ").append(fromResult.sql());
        sql.append(" GROUP BY [").append(groupColumn).append("]");

        // Pivot result aliases participate in client-visible sort just
        // like regular aggregate aliases — translateGroupedSort already
        // honours every alias it is told about. Build an allow list and
        // wire the pivot aliases in alongside the group column.
        String orderBy = translatePivotedSort(sortModel, groupColumn,
                pivotResultFields);
        sql.append(" ORDER BY ").append(orderBy);

        int offset = (page - 1) * pageSize;
        sql.append(" OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
        params.addValue("_offset", offset);
        params.addValue("_pageSize", pageSize);

        return new PivotedBuiltQuery(sql.toString(), params,
                fromResult.warnings(), pivotResultFields, pivotResultColumns);
    }

    /**
     * PR-0.4b: derive an ORDER BY for a pivoted grouped query. Same
     * deterministic-paging contract as {@link #translateGroupedSort}
     * but the allow-list is the group column plus every pivot result
     * alias (instead of the raw aggregation fields).
     */
    private String translatePivotedSort(List<Map<String, String>> sortModel,
                                          String groupColumn,
                                          List<String> pivotResultFields) {
        if (sortModel == null || sortModel.isEmpty()) {
            return "[" + groupColumn + "] ASC";
        }
        Set<String> allowedSortCols = new java.util.HashSet<>();
        allowedSortCols.add(groupColumn);
        allowedSortCols.addAll(pivotResultFields);
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
            return "[" + groupColumn + "] ASC";
        }
        if (!groupColumnIncluded) {
            sb.append(", [").append(groupColumn).append("] ASC");
        }
        return sb.toString();
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
