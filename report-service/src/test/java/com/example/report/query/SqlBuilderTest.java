package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.PivotValue;
import com.example.report.registry.ReportDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class SqlBuilderTest {

    private SqlBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SqlBuilder();
    }

    /**
     * Codex 019e0c99 iter-5 absorb: build a Branch test fixture from a
     * transactional schema name, parsing year+tenantId out of the
     * {@code workcube_mikrolink_<year>_<tenantId>} pattern when present.
     * Lookup defaults to available; specific tests override.
     */
    private static YearlySchemaResolver.Branch branch(String transactionSchema) {
        // Best-effort year/tenantId parse (test fixtures often use synthetic names
        // like "db_2024" — the resolved-schemas plumbing only cares about schemas()).
        return new YearlySchemaResolver.Branch(
                transactionSchema, 2024, 0L, "workcube_mikrolink", true);
    }

    // ── Fixtures ──────────────────────────────────────────────

    private static ReportDefinition tableDef(String source, String schema) {
        return new ReportDefinition(
                "test-report", "v1", "Test", "desc", "cat",
                source, schema, "static", null, null,
                List.of(new ColumnDefinition("id", "ID", "number", 100, false),
                        new ColumnDefinition("name", "Name", "text", 200, false)),
                "id", "ASC",
                new AccessConfig(null, null, null, null));
    }

    private static ReportDefinition queryDef(String sourceQuery, String schema) {
        return new ReportDefinition(
                "custom-query", "v1", "Custom", "desc", "cat",
                null, schema, "static", null, sourceQuery,
                List.of(new ColumnDefinition("amount", "Amount", "number", 120, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));
    }

    private static ReportDefinition yearlyDef() {
        return new ReportDefinition(
                "yearly-report", "v1", "Yearly", "desc", "cat",
                "TRANSACTIONS", "dbo", "yearly", "TxDate", null,
                List.of(new ColumnDefinition("TxDate", "Date", "date", 150, false),
                        new ColumnDefinition("Amount", "Amount", "number", 120, false)),
                "TxDate", "DESC",
                new AccessConfig(null, null, null, null));
    }

    private static final List<String> VISIBLE_COLS = List.of("id", "name");
    private static final List<String> YEARLY_COLS = List.of("TxDate", "Amount");

    // ── buildDataQuery ────────────────────────────────────────

    @Nested
    class BuildDataQuery {

        @Test
        void singleSchema_producesSelectWithPagination() {
            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    tableDef("USERS", "dbo"), VISIBLE_COLS,
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 20);

            assertThat(q.sql()).contains("SELECT [id], [name]");
            assertThat(q.sql()).contains("[dbo].[USERS] WITH (NOLOCK)");
            assertThat(q.sql()).contains("WHERE 1=1");
            assertThat(q.sql()).contains("OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
            assertThat(q.params().getValue("_offset")).isEqualTo(0);
            assertThat(q.params().getValue("_pageSize")).isEqualTo(20);
        }

        @Test
        void singleSchema_page2_offsetCalculation() {
            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    tableDef("USERS", "dbo"), VISIBLE_COLS,
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 3, 50);

            assertThat(q.params().getValue("_offset")).isEqualTo(100);
            assertThat(q.params().getValue("_pageSize")).isEqualTo(50);
        }

        @Test
        void singleSchema_withRlsClause() {
            MapSqlParameterSource rlsParams = new MapSqlParameterSource();
            rlsParams.addValue("company_id", 42);

            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    tableDef("USERS", "dbo"), VISIBLE_COLS,
                    Collections.emptyMap(), Collections.emptyList(),
                    "[company_id] = :company_id", rlsParams, 1, 10);

            assertThat(q.sql()).contains("AND [company_id] = :company_id");
            assertThat(q.params().getValue("company_id")).isEqualTo(42);
        }

        @Test
        void singleSchema_withSortModel() {
            List<Map<String, String>> sort = List.of(
                    Map.of("colId", "name", "sort", "desc"));

            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    tableDef("USERS", "dbo"), VISIBLE_COLS,
                    Collections.emptyMap(), sort,
                    null, null, 1, 10);

            assertThat(q.sql()).contains("ORDER BY [name] DESC");
        }

        @Test
        void singleSchema_noSort_fallsBackToSelectNull() {
            ReportDefinition def = new ReportDefinition(
                    "no-sort", "v1", "NoSort", "desc", "cat",
                    "DATA", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                    null, "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    def, List.of("id"),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 10);

            assertThat(q.sql()).contains("ORDER BY (SELECT NULL)");
        }

        @Test
        void customSourceQuery_wrapsAsSubquery() {
            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    queryDef("SELECT * FROM {schema}.LEDGER WHERE year=2024", "accounting"),
                    List.of("amount"),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 10);

            assertThat(q.sql()).contains("FROM (SELECT * FROM accounting.LEDGER WHERE year=2024) AS _src");
            assertThat(q.sql()).contains("WHERE 1=1");
        }

        @Test
        void multiSchema_producesUnionAll() {
            YearlySchemaResolver.ResolvedSchemas schemas =
                    new YearlySchemaResolver.ResolvedSchemas(List.of(branch("db_2023"), branch("db_2024")));

            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    yearlyDef(), schemas, YEARLY_COLS,
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 20);

            assertThat(q.sql()).contains("UNION ALL");
            assertThat(q.sql()).contains("[db_2023].[TRANSACTIONS] WITH (NOLOCK)");
            assertThat(q.sql()).contains("[db_2024].[TRANSACTIONS] WITH (NOLOCK)");
            assertThat(q.sql()).contains(") AS _u");
        }

        @Test
        void multiSchema_pushesDownRlsIntoEachBranch() {
            YearlySchemaResolver.ResolvedSchemas schemas =
                    new YearlySchemaResolver.ResolvedSchemas(List.of(branch("db_2023"), branch("db_2024")));

            MapSqlParameterSource rlsParams = new MapSqlParameterSource();
            rlsParams.addValue("cid", 5);

            SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                    yearlyDef(), schemas, YEARLY_COLS,
                    Collections.emptyMap(), Collections.emptyList(),
                    "[cid] = :cid", rlsParams, 1, 10);

            // RLS pushed into both branches
            String sql = q.sql();
            int firstWhere = sql.indexOf("AND [cid] = :cid");
            int secondWhere = sql.indexOf("AND [cid] = :cid", firstWhere + 1);
            assertThat(firstWhere).isGreaterThan(0);
            assertThat(secondWhere).isGreaterThan(firstWhere);
            assertThat(q.params().getValue("cid")).isEqualTo(5);
        }
    }

    // ── buildCountQuery ───────────────────────────────────────

    @Nested
    class BuildCountQuery {

        @Test
        void singleSchema_producesCountStar() {
            SqlBuilder.BuiltQuery q = builder.buildCountQuery(
                    tableDef("USERS", "dbo"),
                    Collections.emptyMap(), VISIBLE_COLS,
                    null, null);

            assertThat(q.sql()).startsWith("SELECT COUNT(*)");
            assertThat(q.sql()).contains("[dbo].[USERS] WITH (NOLOCK)");
            assertThat(q.sql()).contains("WHERE 1=1");
            assertThat(q.sql()).doesNotContain("OFFSET");
        }

        @Test
        void multiSchema_countsOverUnion() {
            YearlySchemaResolver.ResolvedSchemas schemas =
                    new YearlySchemaResolver.ResolvedSchemas(List.of(branch("y2023"), branch("y2024")));

            SqlBuilder.BuiltQuery q = builder.buildCountQuery(
                    yearlyDef(), schemas,
                    Collections.emptyMap(), YEARLY_COLS,
                    null, null);

            assertThat(q.sql()).contains("SELECT COUNT(*)");
            assertThat(q.sql()).contains("UNION ALL");
            assertThat(q.sql()).contains(") AS _u");
        }
    }

    // ── buildExportQuery ──────────────────────────────────────

    @Nested
    class BuildExportQuery {

        @Test
        void singleSchema_producesTopNWithSort() {
            List<Map<String, String>> sort = List.of(
                    Map.of("colId", "id", "sort", "asc"));

            SqlBuilder.BuiltQuery q = builder.buildExportQuery(
                    tableDef("USERS", "dbo"), VISIBLE_COLS,
                    Collections.emptyMap(), sort,
                    null, null, 5000);

            assertThat(q.sql()).contains("SELECT TOP(:_maxRows)");
            assertThat(q.sql()).contains("[id], [name]");
            assertThat(q.sql()).contains("ORDER BY [id] ASC");
            assertThat(q.params().getValue("_maxRows")).isEqualTo(5000);
            assertThat(q.sql()).doesNotContain("OFFSET");
        }

        @Test
        void exportWithoutSort_noOrderBy() {
            ReportDefinition def = new ReportDefinition(
                    "no-sort-export", "v1", "Export", "desc", "cat",
                    "DATA", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("val", "Val", "text", 100, false)),
                    null, "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildExportQuery(
                    def, List.of("val"),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1000);

            assertThat(q.sql()).contains("SELECT TOP(:_maxRows)");
            assertThat(q.sql()).doesNotContain("ORDER BY");
        }
    }

    // ── buildGroupedQuery (PR-0.2) ──────────────────────────────

    @Nested
    class BuildGroupedQuery {

        @Test
        void simpleGroupBy_emitsRowCountAndGroupColumn() {
            // Bare GROUP BY without aggregations should still surface
            // _rowCount so AG Grid can show the bucket size under each
            // group node.
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, VISIBLE_COLS,
                    "name", List.of(),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql()).contains("SELECT [name]");
            assertThat(q.sql()).contains("COUNT(*) AS [_rowCount]");
            assertThat(q.sql()).contains("GROUP BY [name]");
            // Default order by group column ASC for deterministic paging.
            assertThat(q.sql()).contains("ORDER BY [name] ASC");
            assertThat(q.sql()).contains("OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
            assertThat(q.params().getValue("_offset")).isEqualTo(0);
            assertThat(q.params().getValue("_pageSize")).isEqualTo(50);
        }

        @Test
        void aggregationsEmittedAsAliasedExpressions() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount", "qty"),
                    "category",
                    List.of(
                            new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("qty", "avg")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("SUM([amount]) AS [amount]")
                    .contains("AVG([qty]) AS [qty]")
                    .contains("GROUP BY [category]");
        }

        @Test
        void aggregationOnGroupColumnDroppedAsTautological() {
            // GROUP BY x; SUM(x) is the same as the raw value, so the
            // builder silently drops it instead of producing an
            // ambiguous SELECT clause.
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, VISIBLE_COLS,
                    "name",
                    List.of(new SqlBuilder.GroupedAggregation("name", "count")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("GROUP BY [name]")
                    .doesNotContain("COUNT([name]) AS [name]");
        }

        @Test
        void aggregationOnHiddenColumnDropped() {
            // Defence-in-depth: even if the controller forwards an
            // aggregation against a non-visible column, the builder
            // refuses to project it so a SQL injection-shaped payload
            // can't reach a hidden field.
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, VISIBLE_COLS,
                    "name",
                    List.of(new SqlBuilder.GroupedAggregation("hidden", "sum")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql()).doesNotContain("[hidden]");
        }

        @Test
        void unknownGroupColumnRejected() {
            ReportDefinition def = tableDef("ORDERS", "dbo");
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedQuery(
                            def, null, VISIBLE_COLS,
                            "ghost", List.of(),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void sortModelOnGroupColumnHonored() {
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, VISIBLE_COLS,
                    "name", List.of(),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "name", "sort", "desc")),
                    null, null, 1, 50);

            assertThat(q.sql()).contains("ORDER BY [name] DESC");
        }

        @Test
        void sortModelOnAggregationAliasHonored() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc")),
                    null, null, 1, 50);

            assertThat(q.sql()).contains("ORDER BY [amount] DESC");
        }

        // ── PR #2: Grouped sort tie-breaker hardening (Codex 019e2695) ───
        // When the SSRM request sorts on an aggregate alias only, two
        // buckets with the same aggregated value were previously left in
        // arbitrary MSSQL order. With OFFSET/FETCH pagination that means
        // a row can be skipped or duplicated across page windows. The
        // translator must append the group column as the final ASC
        // tie-breaker whenever the caller did not already include it.

        @Test
        void groupedSortInjectsGroupColumnTieBreakerWhenAggOnlySort() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc")),
                    null, null, 1, 50);

            // Hardened contract: aggregate alias DESC + group column ASC
            // tie-breaker. Without the tie-breaker, OFFSET/FETCH pagination
            // across same-sum buckets is non-deterministic on MSSQL.
            assertThat(q.sql())
                    .contains("ORDER BY [amount] DESC, [category] ASC OFFSET");
        }

        @Test
        void groupedSortDoesNotDuplicateGroupColumnWhenAlreadyPresent() {
            // If the request already sorts on the group column (last
            // entry or anywhere in the chain), the translator must NOT
            // append a duplicate tie-breaker. A "[category] ASC,
            // [category] ASC" tail would be a regression.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc"),
                            Map.of("colId", "category", "sort", "asc")),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("ORDER BY [amount] DESC, [category] ASC OFFSET")
                    .doesNotContain("[category] ASC, [category] ASC");
        }

        @Test
        void groupedSortHonorsExplicitDescTieBreakerOnGroupColumn() {
            // Defensive: when the caller explicitly chose DESC on the
            // group column, the translator must respect that direction
            // and still produce a deterministic order — no implicit ASC
            // override.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc"),
                            Map.of("colId", "category", "sort", "desc")),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("ORDER BY [amount] DESC, [category] DESC OFFSET");
        }

        @Test
        void groupedSortTieBreakerSurvivesRlsAndFilters() {
            // RLS parity discipline (Codex 019e2695): the tie-breaker
            // must survive the RLS / filter injection paths. Verify that
            // a sort + filter + RLS combination still ends in the
            // [groupColumn] ASC tail.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            MapSqlParameterSource rlsParams = new MapSqlParameterSource()
                    .addValue("_rlsIds", List.of(1L, 2L));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc")),
                    "[OWNER_ID] IN (:_rlsIds)", rlsParams, 1, 50);

            assertThat(q.sql())
                    .contains("WHERE 1=1 AND [OWNER_ID] IN (:_rlsIds)")
                    .contains("ORDER BY [amount] DESC, [category] ASC OFFSET");
        }

        @Test
        void sortModelOnUnknownColumnDropped() {
            // Defensive: a sort entry referencing a non-group, non-agg
            // column is dropped silently. Falls back to default group
            // column ASC so paging stays deterministic.
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, VISIBLE_COLS,
                    "name", List.of(),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "id", "sort", "asc")),
                    null, null, 1, 50);

            assertThat(q.sql()).contains("ORDER BY [name] ASC");
        }

        @Test
        void groupedCountQueryUsesSubquery() {
            ReportDefinition def = tableDef("ORDERS", "dbo");
            SqlBuilder.BuiltQuery q = builder.buildGroupedCountQuery(
                    def, null, VISIBLE_COLS, "name",
                    Collections.emptyMap(), null, null);

            // Total = COUNT(*) over the GROUPED inner subquery, NOT
            // COUNT(DISTINCT) — the latter doesn't compose with the
            // existing UNION ALL FROM clause used by yearly schemas.
            assertThat(q.sql())
                    .contains("SELECT COUNT(*) FROM")
                    .contains("GROUP BY [name]");
        }

        @Test
        void aggregationFunctionNormalizedToLowerCase() {
            // GroupedAggregation accepts mixed case input but stores
            // the canonical lower-case form so SQL generation is
            // deterministic. The SQL itself uppercases for readability.
            SqlBuilder.GroupedAggregation a =
                    new SqlBuilder.GroupedAggregation("amount", "SUM");
            assertThat(a.func()).isEqualTo("sum");

            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category", List.of(a),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql()).contains("SUM([amount])");
        }

        @Test
        void invalidAggregationFunctionRejected_unknownToken() {
            // Codex 019e2695 iter-5 absorb: PR #6a accepts `median`, so
            // the negative case here must reference a permanently
            // invalid token rather than a roadmap function. Same
            // discipline applies to `percentile` (PR #6b) and
            // `weightedavg` (PR-0.4) — they belong in positive tests
            // once those PRs land.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new SqlBuilder.GroupedAggregation("col", "garbage_xyz"));
        }

        @Test
        void medianAggregationFunctionAccepted() {
            SqlBuilder.GroupedAggregation a =
                    new SqlBuilder.GroupedAggregation("amount", "median");
            assertThat(a.func()).isEqualTo("median");
            assertThat(a.field()).isEqualTo("amount");
        }

        @Test
        void medianAggregationFunctionNormalizedToLowerCase() {
            SqlBuilder.GroupedAggregation a =
                    new SqlBuilder.GroupedAggregation("amount", "MEDIAN");
            assertThat(a.func()).isEqualTo("median");
        }

        @Test
        void blankAggregationFieldRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new SqlBuilder.GroupedAggregation("", "sum"));
        }

        // ── Extended aggregate funcs (Codex thread 019e2695) ─────────────
        // PR-0.4z: distinctcount → COUNT(DISTINCT [col]); stddev → STDEV;
        // stddevp → STDEVP.
        // PR #6a: median → PERCENTILE_CONT window + outer MAX collapse.
        // percentile (PR #6b, aggParams contract) and weightedAvg
        // (PR-0.4) remain on the roadmap and stay rejected here.

        @Test
        void appliesStddevAggregation() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "stddev")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("STDEV([amount]) AS [amount]")
                    .contains("GROUP BY [category]");
        }

        @Test
        void appliesStddevpAggregation() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "stddevp")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("STDEVP([amount]) AS [amount]")
                    .contains("GROUP BY [category]");
        }

        @Test
        void appliesDistinctCountAggregation() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("user_id", "User", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "user_id"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("user_id", "distinctcount")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("COUNT(DISTINCT [user_id]) AS [user_id]")
                    .contains("GROUP BY [category]")
                    .doesNotContain("DISTINCTCOUNT(");
        }

        @Test
        void distinctCountAggregationRespectsRlsClause() {
            // Codex 019e2695 review note: every SQL-shape changing PR
            // must include at least one RLS parity assertion to guard
            // against future regressions where an aggregation render
            // bypasses the RLS WHERE injection path.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("user_id", "User", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            MapSqlParameterSource rlsParams = new MapSqlParameterSource()
                    .addValue("_rlsIds", List.of(1L, 2L));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "user_id"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("user_id", "distinctcount")),
                    Collections.emptyMap(), Collections.emptyList(),
                    "[OWNER_ID] IN (:_rlsIds)", rlsParams, 1, 50);

            // Codex 019e2695 review iter-2: tighten the parity assertion
            // from a substring contains() to an exact "WHERE 1=1 AND ..."
            // join so a future refactor that drops the RLS append from
            // buildFromClause is caught at the SQL-shape boundary, not
            // just at the textual substring level.
            assertThat(q.sql())
                    .contains("COUNT(DISTINCT [user_id]) AS [user_id]")
                    .contains("WHERE 1=1 AND [OWNER_ID] IN (:_rlsIds)");
            assertThat(q.params().getValue("_rlsIds"))
                    .isEqualTo(List.of(1L, 2L));
        }

        @Test
        void appliesMedianAggregation_emitsPercentileContWindowAndOuterMaxCollapse() {
            // Codex 019e2695 PR #6a contract: median renders as an
            // inner-subquery PERCENTILE_CONT window aliased as
            // [__median_<field>], with the outer SELECT collapsing
            // bucket rows via MAX(__median_<field>) AS [<field>].
            // External alias stays [<field>] so the SSRM response
            // shape matches the simple-aggregate contract.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Inner subquery: PERCENTILE_CONT window aliased canonically.
            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [amount])")
                    .contains("OVER (PARTITION BY [category])")
                    .contains("AS [__median_amount]");
            // Outer SELECT: MAX collapse + external alias [amount].
            assertThat(q.sql())
                    .contains("MAX([__median_amount]) AS [amount]");
            // Subquery wrapper.
            assertThat(q.sql())
                    .contains("FROM (")
                    .contains(") AS _med")
                    .contains("GROUP BY [category]");
            // Codex 019e2695 iter-6: defensive assertion that median
            // does NOT travel through the simple-aggregate render path.
            // `MEDIAN([col])` would be invalid T-SQL — the inner
            // subquery + outer MAX collapse is the only valid shape.
            assertThat(q.sql()).doesNotContain("MEDIAN([");
        }

        @Test
        void medianAggregationMixedWithSum_keepsBothOnTheSameOuterSelect() {
            // Different fields: SUM on `amount`, MEDIAN on `cost`.
            // The simple SUM stays on the existing render path and the
            // median piggybacks the inner subquery wrapper.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("cost", "Cost", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount", "cost"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("cost", "median")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("SUM([amount]) AS [amount]")
                    .contains("MAX([__median_cost]) AS [cost]")
                    .contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [cost])");
        }

        @Test
        void medianAggregation_preservesRlsClauseInsideInnerSubquery() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            MapSqlParameterSource rlsParams = new MapSqlParameterSource()
                    .addValue("_rlsIds", List.of(1L, 2L));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                    Collections.emptyMap(), Collections.emptyList(),
                    "[OWNER_ID] IN (:_rlsIds)", rlsParams, 1, 50);

            // RLS clause must land inside the FROM-clause filter, which
            // is captured by the inner subquery alongside the
            // PERCENTILE_CONT window.
            assertThat(q.sql())
                    .contains("WHERE 1=1 AND [OWNER_ID] IN (:_rlsIds)")
                    .contains("PERCENTILE_CONT(0.5)")
                    .contains("MAX([__median_amount]) AS [amount]");
            assertThat(q.params().getValue("_rlsIds"))
                    .isEqualTo(List.of(1L, 2L));
        }

        // ── PR #6b: percentilecont (PERCENTILE_CONT(<literal>) window) ───
        // Codex 019e2695 absorb: percentile rank is rendered as a
        // validated numeric literal (BigDecimal.stripTrailingZeros)
        // rather than a bind param so MSSQL's syntactic requirement
        // stays satisfied. The validation lives at the controller
        // layer; SqlBuilder trusts the upstream contract.

        @Test
        void percentileContAggregation_emitsLiteralPercentile() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "amount", "percentilecont",
                            Map.of("percentile", 0.9))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Literal render, not bind param.
            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY [amount])")
                    .contains("OVER (PARTITION BY [category])")
                    .contains("AS [__pctcont_amount]")
                    .contains("MAX([__pctcont_amount]) AS [amount]");
            // Defensive: no bind-param shape leaked into the SQL.
            assertThat(q.sql()).doesNotContain("PERCENTILE_CONT(:");
            // External alias contract: [amount], not [amount_p90] or similar.
            assertThat(q.sql()).doesNotContain("[amount_p");
        }

        @Test
        void percentileContAggregation_p25LiteralStripTrailingZeros() {
            // BigDecimal.valueOf(0.25).stripTrailingZeros().toPlainString()
            // → "0.25" — guard against locale-dependent Double.toString
            // surprises (e.g. "0,25" or "2.5E-1").
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "amount", "percentilecont",
                            Map.of("percentile", 0.25))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.25)");
        }

        @Test
        void percentileContAggregation_p1RendersAsInteger() {
            // p=1.0 should render as "1" (stripTrailingZeros), not "1.0",
            // so the literal is consistent across decimal/integer rank
            // requests.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "amount", "percentilecont",
                            Map.of("percentile", 1.0))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(1)");
        }

        @Test
        void percentileContAggregationMissingParam_rejected() {
            // SqlBuilder.percentileLiteral throws IllegalStateException
            // when params is null/missing. Controller validation is the
            // primary line of defense, but the builder must not silently
            // emit invalid SQL if it ever gets here.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> {
                        ReportDefinition def = new ReportDefinition(
                                "test", "v1", "T", "d", "c",
                                "ORDERS", "dbo", "static", null, null,
                                List.of(new ColumnDefinition("category", "Cat", "text", 100, false),
                                        new ColumnDefinition("amount", "Amount", "number", 120, false)),
                                "category", "ASC",
                                new AccessConfig(null, null, null, null));
                        builder.buildGroupedQuery(
                                def, null, List.of("category", "amount"),
                                "category",
                                List.of(new SqlBuilder.GroupedAggregation(
                                        "amount", "percentilecont")),  // null params
                                Collections.emptyMap(), Collections.emptyList(),
                                null, null, 1, 50);
                    });
        }

        @Test
        void medianAndPercentileCont_coexistInSameInnerSubquery() {
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("cost", "Cost", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount", "cost"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median"),
                            new SqlBuilder.GroupedAggregation(
                                    "cost", "percentilecont",
                                    Map.of("percentile", 0.9))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Both window expressions land in the same inner subquery,
            // each under its own internal alias.
            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [amount])")
                    .contains("PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY [cost])")
                    .contains("AS [__median_amount]")
                    .contains("AS [__pctcont_cost]")
                    .contains("MAX([__median_amount]) AS [amount]")
                    .contains("MAX([__pctcont_cost]) AS [cost]");
        }

        @Test
        void medianAggregation_sortOnMedianFieldGetsTieBreaker() {
            // PR #2 tie-breaker discipline composes with PR #6a median:
            // sorting on the median alias must still append the group
            // column as the deterministic ASC tail.
            ReportDefinition def = new ReportDefinition(
                    "test-report", "v1", "Test", "desc", "cat",
                    "ORDERS", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "amount"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "amount", "sort", "desc")),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("ORDER BY [amount] DESC, [category] ASC OFFSET");
        }

        @Test
        void stddevFunctionNormalizedToLowerCase() {
            // Mixed-case input ("STDEV") must canonicalise to "stddev" so
            // SQL generation stays deterministic regardless of how the
            // request payload or registry default is cased.
            SqlBuilder.GroupedAggregation a =
                    new SqlBuilder.GroupedAggregation("amount", "STDDEV");
            assertThat(a.func()).isEqualTo("stddev");
        }
    }

    /**
     * Codex 019e0c99 iter-3 §B helper coverage. Per-branch render of the
     * {@code SETUP_PROCESS_CAT} relation fragment must produce alias-preserving
     * compile-safe SQL whether the per-tenant lookup table exists or not.
     */
    @Nested
    class RenderTenantSetupProcessCatRelation {

        @Test
        void available_rendersRealTableWithNolockHint() {
            YearlySchemaResolver.Branch b = new YearlySchemaResolver.Branch(
                    "workcube_mikrolink_2026_35", 2026, 35L,
                    "workcube_mikrolink_35", true);
            java.util.List<DegradationWarning> warnings = new java.util.ArrayList<>();
            String fragment = builder.renderTenantSetupProcessCatRelation(
                    b, "fin-muhasebe-detay", warnings);
            assertThat(fragment).isEqualTo(
                    "[workcube_mikrolink_35].[SETUP_PROCESS_CAT] SPC WITH (NOLOCK)");
            assertThat(warnings).isEmpty();
        }

        @Test
        void unavailable_rendersEmptyRowsetWithoutNolock_emitsWarning() {
            YearlySchemaResolver.Branch b = new YearlySchemaResolver.Branch(
                    "workcube_mikrolink_2026_50", 2026, 50L,
                    "workcube_mikrolink_50", false);
            java.util.List<DegradationWarning> warnings = new java.util.ArrayList<>();
            String fragment = builder.renderTenantSetupProcessCatRelation(
                    b, "fin-muhasebe-detay", warnings);
            // Alias preserved (`SPC`), columns CAST NULL, WHERE 1=0 → 0 rows.
            assertThat(fragment).contains("SPC");
            assertThat(fragment).contains("CAST(NULL AS int) AS PROCESS_CAT_ID");
            assertThat(fragment).contains("CAST(NULL AS nvarchar(4000)) AS PROCESS_CAT");
            assertThat(fragment).contains("WHERE 1 = 0");
            // Codex iter-3 explicit: NOLOCK only on real tables.
            assertThat(fragment).doesNotContain("NOLOCK");
            // Schema name MUST NOT leak into the unavailable branch.
            assertThat(fragment).doesNotContain("workcube_mikrolink_50");
            // Warning surfaced for header propagation.
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).code())
                    .isEqualTo(DegradationWarning.CODE_TENANT_LOOKUP_UNAVAILABLE);
            assertThat(warnings.get(0).tenantId()).isEqualTo("50");
            assertThat(warnings.get(0).reportKey()).isEqualTo("fin-muhasebe-detay");
            assertThat(warnings.get(0).table()).isEqualTo("SETUP_PROCESS_CAT");
        }

        @Test
        void nullBranch_rendersEmptyRowsetDefensively() {
            java.util.List<DegradationWarning> warnings = new java.util.ArrayList<>();
            String fragment = builder.renderTenantSetupProcessCatRelation(
                    null, "fin-muhasebe-detay", warnings);
            assertThat(fragment).contains("WHERE 1 = 0");
            // Null branch → no warning enqueued (no tenantId to attach).
            assertThat(warnings).isEmpty();
        }
    }

    // ── PR-0.4c: weightedavg aggregation ──────────────────────────

    @Nested
    class WeightedAverageAggregation {

        @Test
        void rendersNullSafeWeightedRatioInGroupedQuery() {
            // SUM(value * weight) / NULLIF(SUM(CASE WHEN value IS NOT
            // NULL AND weight IS NOT NULL THEN weight END), 0)
            // — rows where either operand is null fall out of both
            // numerator and denominator (MSSQL AVG semantic).
            ReportDefinition def = new ReportDefinition(
                    "weighted-report", "v1", "Weighted", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "price", "qty"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "price", "weightedavg",
                            Map.of("weightField", "qty"))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("SUM([price] * [qty]) / NULLIF(SUM(CASE WHEN [price] IS NOT NULL"
                            + " AND [qty] IS NOT NULL THEN [qty] END), 0) AS [price]")
                    .contains("GROUP BY [category]");
        }

        @Test
        void weightedavgWithoutWeightFieldRejectedByBuilder() {
            // Codex 019e2acc iter-2: the SQL builder's projection
            // loop now eagerly validates the weight reference
            // (defence in depth — the controller sanitizeAggParams
            // also enforces this). A future caller that bypasses
            // the controller must still fail loudly with a structured
            // IAE rather than producing broken SQL like
            // `SUM([price] * [])`.
            ReportDefinition def = new ReportDefinition(
                    "weighted-report", "v1", "Weighted", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
            SqlBuilder.GroupedAggregation a =
                    new SqlBuilder.GroupedAggregation("price", "weightedavg", null);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedQuery(
                            def, null, List.of("category", "price"),
                            "category", List.of(a),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void weightedavgWeightColumnAddedToFromClauseProjection() {
            // Codex 019e2acc iter-2 blocker absorb. The outer SQL
            // references SUM([price] * [qty]); the inner FROM clause
            // (single-schema OR UNION ALL multi-year) must SELECT [qty]
            // alongside the group column + value field. Without the
            // projection fix multi-schema branches emit SELECT
            // [category], [price] while outer SUM references absent
            // [qty] → MSSQL "Invalid column name".
            ReportDefinition def = new ReportDefinition(
                    "weighted-report", "v1", "Weighted", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "price", "qty"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "price", "weightedavg",
                            Map.of("weightField", "qty"))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Single-schema path emits the full table with all columns
            // (no inner SELECT), but multi-schema UNION ALL needs the
            // weight column explicitly in each arm's SELECT — covered
            // by the next test. Single-schema still must reference
            // [qty] in the outer aggregate expression.
            assertThat(q.sql())
                    .contains("[dbo].[TXN]")
                    .contains("SUM([price] * [qty])");
        }

        @Test
        void weightedavgWeightInMultiSchemaUnionAllProjection() {
            // Yearly schemaMode → multi-schema UNION ALL FROM clause.
            // Each UNION arm SELECTs the projected column set; the
            // weight column must appear there so the outer aggregate
            // on _u alias can dereference it.
            ReportDefinition def = new ReportDefinition(
                    "yearly-weighted", "v1", "Yearly Weighted", "desc", "cat",
                    "TXN", "dbo", "yearly", "txnDate", null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            YearlySchemaResolver.ResolvedSchemas multiYear =
                    new YearlySchemaResolver.ResolvedSchemas(
                            List.of(branch("workcube_mikrolink_2025"),
                                    branch("workcube_mikrolink_2026")));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, multiYear, List.of("category", "price", "qty"),
                    "category",
                    List.of(new SqlBuilder.GroupedAggregation(
                            "price", "weightedavg",
                            Map.of("weightField", "qty"))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Both UNION arms must include [qty] in the SELECT.
            // Count [qty] occurrences — expect ≥ 3 (2 SELECT slots
            // in UNION arms + ≥ 1 outer aggregate reference).
            int qtyCount = q.sql().split("\\[qty\\]").length - 1;
            assertThat(qtyCount).isGreaterThanOrEqualTo(3);
            assertThat(q.sql())
                    .contains("SUM([price] * [qty])")
                    .contains("workcube_mikrolink_2025")
                    .contains("workcube_mikrolink_2026");
        }

        @Test
        void weightedavgWeightFieldNotVisibleRejected() {
            // Builder-level visibility check (defence-in-depth — the
            // controller layer also validates). A future caller that
            // bypasses sanitizeAggregations must still fail closed
            // here before broken SQL hits MSSQL.
            ReportDefinition def = new ReportDefinition(
                    "weighted-report", "v1", "Weighted", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedQuery(
                            def, null, List.of("category", "price"),
                            "category",
                            List.of(new SqlBuilder.GroupedAggregation(
                                    "price", "weightedavg",
                                    Map.of("weightField", "qty"))), // 'qty' not visible
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void weightedavgWeightFieldSameAsValueFieldRejected() {
            ReportDefinition def = new ReportDefinition(
                    "weighted-report", "v1", "Weighted", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedQuery(
                            def, null, List.of("category", "price"),
                            "category",
                            List.of(new SqlBuilder.GroupedAggregation(
                                    "price", "weightedavg",
                                    Map.of("weightField", "price"))),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void weightedavgMixedWithOtherAggsInSameGroupedQuery() {
            // Multiple value cols, one weighted, others standard.
            ReportDefinition def = new ReportDefinition(
                    "mixed-report", "v1", "Mixed", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("price", "Price", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));

            SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                    def, null, List.of("category", "price", "qty", "amount"),
                    "category",
                    List.of(
                            new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation(
                                    "price", "weightedavg",
                                    Map.of("weightField", "qty"))),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("SUM([amount]) AS [amount]")
                    .contains("SUM([price] * [qty]) / NULLIF(")
                    .contains("AS [price]");
        }
    }

    // ── buildPivotedGroupedQuery (PR-0.4b) ───────────────────────

    @Nested
    class BuildPivotedGroupedQuery {

        private ReportDefinition pivotDef() {
            // 4-column report: group dimension + pivot dimension + 2 value
            // columns the pivot bucketises. Mirrors the canary shape the
            // controller validates against the registry at runtime.
            return new ReportDefinition(
                    "pivot-report", "v1", "Pivot", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("status", "Status", "text", 80, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 80, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
        }

        private static final List<String> PIVOT_COLS =
                List.of("category", "status", "amount", "qty");

        @Test
        void renderEmitsRowCountAndPivotAliasesInDeterministicOrder() {
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A", "Aktif"),
                            new PivotValue("P", "Pasif")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            // Outer iteration is pivot-first, agg-second so adjacent
            // result columns belong to the same bucket.
            assertThat(q.pivotResultFields()).containsExactly(
                    "pvt__status__A__sum__amount",
                    "pvt__status__P__sum__amount");
            assertThat(q.sql())
                    .contains("SELECT [category]")
                    .contains("COUNT(*) AS [_rowCount]")
                    .contains("SUM(CASE WHEN [status] = :_pivot_0 THEN [amount] ELSE 0 END)")
                    .contains("AS [pvt__status__A__sum__amount]")
                    .contains("SUM(CASE WHEN [status] = :_pivot_1 THEN [amount] ELSE 0 END)")
                    .contains("AS [pvt__status__P__sum__amount]")
                    .contains("GROUP BY [category]")
                    .contains("ORDER BY [category] ASC");
            // Pivot literals MUST bind as named params; no raw quoted
            // literal in the generated SQL.
            assertThat(q.sql()).doesNotContain("= 'A'");
            assertThat(q.sql()).doesNotContain("= 'P'");
            assertThat(q.params().getValue("_pivot_0")).isEqualTo("A");
            assertThat(q.params().getValue("_pivot_1")).isEqualTo("P");
        }

        @Test
        void avgUsesNoElseSoOutOfBucketRowsDoNotBiasDenominator() {
            // AVG with `ELSE 0` would drag every out-of-bucket row into
            // the denominator and pull the average towards zero. The
            // renderer omits the ELSE branch so MSSQL evaluates AVG
            // over non-null rows only.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "avg")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("AVG(CASE WHEN [status] = :_pivot_0 THEN [amount] END)")
                    // No ELSE branch under AVG.
                    .doesNotContain("AVG(CASE WHEN [status] = :_pivot_0 THEN [amount] ELSE");
        }

        @Test
        void countOmitsElseSoNullsExcluded() {
            // count(field) translates to COUNT(field) — pivot variant
            // keeps the legacy "non-null count" semantic by omitting
            // the ELSE branch so out-of-bucket rows fall to NULL.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "count")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("COUNT(CASE WHEN [status] = :_pivot_0 THEN [amount] END)")
                    .doesNotContain("ELSE 0");
        }

        @Test
        void distinctCountWrapsCaseInsideDistinct() {
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "distinctcount")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("COUNT(DISTINCT CASE WHEN [status] = :_pivot_0 THEN [amount] END)");
        }

        @Test
        void multipleValueColsProduceCartesianAliases() {
            // pivotValues × valueCols product surfaces every combination.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A"), new PivotValue("P")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("qty", "avg")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.pivotResultFields()).containsExactly(
                    "pvt__status__A__sum__amount",
                    "pvt__status__A__avg__qty",
                    "pvt__status__P__sum__amount",
                    "pvt__status__P__avg__qty");
        }

        @Test
        void unicodePivotValueProducesSafeAlias() {
            // Workcube enums can ship Turkish characters / dashes.
            // Sanitisation must keep the SQL alias identifier-safe
            // while the underlying CASE WHEN literal stays untouched
            // (bound as a named param).
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("İş-Yeri", "İş Yeri")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            String alias = q.pivotResultFields().get(0);
            assertThat(alias).startsWith("pvt__status__");
            assertThat(alias).endsWith("__sum__amount");
            // Non-ASCII codepoints collapse but the alias remains
            // syntactically a valid SQL identifier (alpha + underscore).
            assertThat(alias).matches("pvt__status__[a-zA-Z0-9_]+__sum__amount");
            // The bound param keeps the original SQL value byte-for-byte.
            assertThat(q.params().getValue("_pivot_0")).isEqualTo("İş-Yeri");
        }

        @Test
        void pivotColumnEqualToGroupColumnRejected() {
            ReportDefinition def = pivotDef();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null, PIVOT_COLS,
                            "category", "category",
                            List.of(new PivotValue("A")),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void unknownPivotColumnRejected() {
            ReportDefinition def = pivotDef();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null, PIVOT_COLS,
                            "category", "ghost",
                            List.of(new PivotValue("A")),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void emptyPivotValuesRejected() {
            ReportDefinition def = pivotDef();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null, PIVOT_COLS,
                            "category", "status",
                            List.of(),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void outputColumnBudgetExceededRejected() {
            // pivotValues(8) * valueCols(5) = 40 > MAX_PIVOT_OUTPUT_COLUMNS(32).
            ReportDefinition def = new ReportDefinition(
                    "wide", "v1", "Wide", "desc", "cat",
                    "T", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("status", "Status", "text", 80, false),
                            new ColumnDefinition("v1", "v1", "number", 80, false),
                            new ColumnDefinition("v2", "v2", "number", 80, false),
                            new ColumnDefinition("v3", "v3", "number", 80, false),
                            new ColumnDefinition("v4", "v4", "number", 80, false),
                            new ColumnDefinition("v5", "v5", "number", 80, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
            List<PivotValue> eightValues = List.of(
                    new PivotValue("a"), new PivotValue("b"),
                    new PivotValue("c"), new PivotValue("d"),
                    new PivotValue("e"), new PivotValue("f"),
                    new PivotValue("g"), new PivotValue("h"));
            List<SqlBuilder.GroupedAggregation> fiveAggs = List.of(
                    new SqlBuilder.GroupedAggregation("v1", "sum"),
                    new SqlBuilder.GroupedAggregation("v2", "sum"),
                    new SqlBuilder.GroupedAggregation("v3", "sum"),
                    new SqlBuilder.GroupedAggregation("v4", "sum"),
                    new SqlBuilder.GroupedAggregation("v5", "sum"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null,
                            List.of("category", "status", "v1", "v2", "v3", "v4", "v5"),
                            "category", "status",
                            eightValues, fiveAggs,
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void medianAggregationInsidePivotRejected() {
            ReportDefinition def = pivotDef();
            // GroupedAggregation accepts median (window path), but pivot
            // builder rejects it because PERCENTILE_CONT cannot be
            // composed inside a CASE WHEN bucket without a wrapper SQL
            // PR-0.4b intentionally postpones.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null, PIVOT_COLS,
                            "category", "status",
                            List.of(new PivotValue("A")),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void aggregationOnPivotColumnDroppedAsTautological() {
            // Pivoting on a value column would re-emit its own bucket
            // membership as a numeric aggregate; the builder drops the
            // entry rather than producing ambiguous SQL.
            ReportDefinition def = pivotDef();
            // Pivoting "status" while aggregating "status" would loop
            // status into both sides — controller would reject upstream
            // (status is not aggregatable), so we exercise the builder
            // defence-in-depth path by passing it directly.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedQuery(
                            def, null, PIVOT_COLS,
                            "category", "status",
                            List.of(new PivotValue("A")),
                            // Only aggregation targets pivot column → after
                            // drop the sanitized list is empty → builder
                            // throws "requires at least one valid agg".
                            List.of(new SqlBuilder.GroupedAggregation("status", "count")),
                            Collections.emptyMap(), Collections.emptyList(),
                            null, null, 1, 50));
        }

        @Test
        void filterModelPredicatePushedDownAlongsidePivot() {
            ReportDefinition def = pivotDef();
            Map<String, Object> filterModel = Map.of(
                    "category", Map.of("type", "equals", "filter", "x"));
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    filterModel, Collections.emptyList(),
                    null, null, 1, 50);

            // Filter clause lands inside the FROM clause WHERE block,
            // not on the outer pivot expression — RLS+filter must run
            // BEFORE the pivot aggregation.
            assertThat(q.sql())
                    .contains("WHERE 1=1")
                    .contains("[category] =");
            // Pivot literal is still bound to the named param.
            assertThat(q.params().getValue("_pivot_0")).isEqualTo("A");
        }

        @Test
        void pivotResultColumnsAlignWithPivotResultFieldsByIndex() {
            // PR-0.4d-be (Codex 019e2695): the two response envelope
            // lists share the same ordering so frontend can index either
            // list with the same row pointer. Builder asserts the
            // invariant at construction; this regression-pin makes sure
            // a future loop refactor can't desync them.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A", "Aktif"),
                            new PivotValue("P", "Pasif")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("qty", "avg")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.pivotResultColumns()).hasSize(q.pivotResultFields().size());
            for (int i = 0; i < q.pivotResultColumns().size(); i++) {
                assertThat(q.pivotResultColumns().get(i).field())
                        .isEqualTo(q.pivotResultFields().get(i));
            }
            // Spot-check semantic metadata so a regression in alias
            // packing also surfaces here.
            com.example.report.query.PivotResultColumn first =
                    q.pivotResultColumns().get(0);
            assertThat(first.pivotField()).isEqualTo("status");
            assertThat(first.pivotValue()).isEqualTo("A");
            assertThat(first.pivotLabel()).isEqualTo("Aktif");
            assertThat(first.aggFunc()).isEqualTo("sum");
            assertThat(first.valueField()).isEqualTo("amount");
        }

        @Test
        void pivotResultColumnsCarryLabelWhenRegistryShipsObjectForm() {
            // Object-form pivotValues (PR-0.4b registry) preserve the
            // user-facing label through to the response envelope so the
            // frontend renders "Aktif" rather than "A" in the secondary
            // header.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A", "Aktif")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.pivotResultColumns()).hasSize(1);
            assertThat(q.pivotResultColumns().get(0).pivotLabel())
                    .isEqualTo("Aktif");
            assertThat(q.pivotResultColumns().get(0).pivotValue())
                    .isEqualTo("A");
        }

        @Test
        void pivotResultColumnsLabelDefaultsToValueOnShortFormRegistry() {
            // Short-form `pivotValues: ["A", "B"]` collapses label==value.
            // The metadata envelope reflects this so the frontend
            // doesn't have to invent a fallback.
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), Collections.emptyList(),
                    null, null, 1, 50);

            assertThat(q.pivotResultColumns().get(0).pivotLabel())
                    .isEqualTo("A");
        }

        @Test
        void aliasCollisionFromSanitizationRejected() {
            // Codex 019e2695 iter-2 P1: two distinct registry values
            // ("A-B" and "A/B") both sanitise into the `A_B` alias key.
            // Without the collision check the SQL builder would emit
            // two columns named `pvt__status__A_B__sum__amount`, which
            // AG Grid cannot disambiguate and SQL Server treats as
            // undefined-result-set duplication. The builder must fail
            // closed so registry maintainers see the conflict instead
            // of debugging missing pivot columns on the frontend.
            ReportDefinition def = pivotDef();
            IllegalArgumentException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class,
                            () -> builder.buildPivotedGroupedQuery(
                                    def, null, PIVOT_COLS,
                                    "category", "status",
                                    List.of(new PivotValue("A-B"),
                                            new PivotValue("A/B")),
                                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                                    Collections.emptyMap(), Collections.emptyList(),
                                    null, null, 1, 50));
            assertThat(ex.getMessage())
                    .contains("Pivot alias collision")
                    .contains("A_B");
        }

        @Test
        void sortOnPivotResultFieldHonored() {
            ReportDefinition def = pivotDef();
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                    def, null, PIVOT_COLS,
                    "category", "status",
                    List.of(new PivotValue("A")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "pvt__status__A__sum__amount", "sort", "desc")),
                    null, null, 1, 50);

            assertThat(q.sql())
                    .contains("ORDER BY [pvt__status__A__sum__amount] DESC")
                    // Stable tie-breaker on the group column for
                    // deterministic OFFSET/FETCH paging.
                    .contains(", [category] ASC");
        }
    }

    // ── PR-0.5a: buildGrandTotalQuery ────────────────────────────

    @Nested
    class BuildGrandTotalQuery {

        private ReportDefinition totalDef() {
            return new ReportDefinition(
                    "total-report", "v1", "Total", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
        }

        @Test
        void emitsAggregateExpressionsWithoutGroupBy() {
            // Core PR-0.5a contract: no GROUP BY, no OFFSET/FETCH,
            // single-row aggregate over the full filtered rowset.
            SqlBuilder.BuiltQuery q = builder.buildGrandTotalQuery(
                    totalDef(), null, List.of("category", "amount", "qty"),
                    List.of(
                            new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("qty", "avg")),
                    Collections.emptyMap(),
                    null, null);

            assertThat(q.sql())
                    .contains("SUM([amount]) AS [amount]")
                    .contains("AVG([qty]) AS [qty]")
                    .contains("[dbo].[TXN]")
                    .doesNotContain("GROUP BY")
                    .doesNotContain("OFFSET")
                    .doesNotContain("FETCH NEXT");
        }

        @Test
        void wrapsPercentileWindowAggsInInnerSelect() {
            // median / percentilecont go through PERCENTILE_CONT
            // window function with OVER () (no PARTITION BY) — global
            // grand total semantic. Outer MAX collapses the window
            // result to a single deterministic row.
            SqlBuilder.BuiltQuery q = builder.buildGrandTotalQuery(
                    totalDef(), null, List.of("category", "amount", "qty"),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                    Collections.emptyMap(),
                    null, null);

            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [amount])")
                    .contains("OVER ()")
                    .contains("MAX([__median_amount]) AS [amount]")
                    .doesNotContain("PARTITION BY")
                    .doesNotContain("GROUP BY");
        }

        @Test
        void weightedavgProjectsWeightFieldIntoFromClause() {
            // PR-0.4c projection contract: weightField must reach the
            // FROM clause SELECT so the outer SUM([value] * [weight])
            // can resolve it. Grand total path inherits the same
            // contract.
            SqlBuilder.BuiltQuery q = builder.buildGrandTotalQuery(
                    totalDef(), null, List.of("category", "amount", "qty"),
                    List.of(new SqlBuilder.GroupedAggregation(
                            "amount", "weightedavg",
                            Map.of("weightField", "qty"))),
                    Collections.emptyMap(),
                    null, null);

            assertThat(q.sql())
                    .contains("SUM([amount] * [qty])")
                    .contains("NULLIF(SUM(CASE WHEN [amount] IS NOT NULL AND [qty] IS NOT NULL THEN [qty] END), 0)")
                    .contains("AS [amount]");
        }

        @Test
        void emptyAggregationsRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGrandTotalQuery(
                            totalDef(), null, List.of("category", "amount"),
                            List.of(),
                            Collections.emptyMap(), null, null));
        }

        @Test
        void aggregationsAllPointingToHiddenColumnsRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGrandTotalQuery(
                            totalDef(), null, List.of("category"),
                            List.of(new SqlBuilder.GroupedAggregation("ghost", "sum")),
                            Collections.emptyMap(), null, null));
        }

        @Test
        void filterPredicatePushedDownAlongsideGrandTotal() {
            Map<String, Object> filterModel = Map.of(
                    "category", Map.of("type", "equals", "filter", "x"));
            SqlBuilder.BuiltQuery q = builder.buildGrandTotalQuery(
                    totalDef(), null, List.of("category", "amount"),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    filterModel, null, null);

            assertThat(q.sql())
                    .contains("WHERE 1=1")
                    .contains("[category] =")
                    .contains("SUM([amount]) AS [amount]");
        }
    }

    // ── PR-0.5b: buildGroupedExportQuery + buildPivotedGroupedExportQuery ──

    @Nested
    class BuildGroupedExportQuery {

        private ReportDefinition exportDef() {
            return new ReportDefinition(
                    "export-report", "v1", "Export", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("region", "Region", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false),
                            new ColumnDefinition("qty", "Qty", "number", 100, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
        }

        @Test
        void emitsTopCapWithoutOffsetFetch() {
            SqlBuilder.BuiltQuery q = builder.buildGroupedExportQuery(
                    exportDef(), null,
                    List.of("category", "region", "amount"),
                    List.of("category"),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), null,
                    null, null, 500000);

            assertThat(q.sql())
                    .contains("SELECT TOP(:_maxRows)")
                    .contains("[category]")
                    .contains("COUNT(*) AS [_rowCount]")
                    .contains("SUM([amount]) AS [amount]")
                    .contains("GROUP BY [category]")
                    .doesNotContain("OFFSET")
                    .doesNotContain("FETCH NEXT");
            assertThat(q.params().getValue("_maxRows")).isEqualTo(500000);
        }

        @Test
        void multiLevelGroupBy() {
            SqlBuilder.BuiltQuery q = builder.buildGroupedExportQuery(
                    exportDef(), null,
                    List.of("category", "region", "amount", "qty"),
                    List.of("category", "region"),
                    List.of(
                            new SqlBuilder.GroupedAggregation("amount", "sum"),
                            new SqlBuilder.GroupedAggregation("qty", "avg")),
                    Collections.emptyMap(), null,
                    null, null, 100);

            assertThat(q.sql())
                    .contains("[category]")
                    .contains("[region]")
                    .contains("SUM([amount]) AS [amount]")
                    .contains("AVG([qty]) AS [qty]")
                    .contains("GROUP BY [category], [region]")
                    // Both group cols default-appended to ORDER BY for
                    // deterministic exports.
                    .contains("ORDER BY [category] ASC, [region] ASC");
        }

        @Test
        void groupColumnOutsideVisibleSetRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedExportQuery(
                            exportDef(), null,
                            List.of("category", "amount"),
                            List.of("hidden"),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), null,
                            null, null, 100));
        }

        @Test
        void emptyGroupColumnsRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildGroupedExportQuery(
                            exportDef(), null,
                            List.of("category", "amount"),
                            List.of(),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), null,
                            null, null, 100));
        }

        @Test
        void weightedavgProjectsWeightFieldInGroupedExport() {
            SqlBuilder.BuiltQuery q = builder.buildGroupedExportQuery(
                    exportDef(), null,
                    List.of("category", "amount", "qty"),
                    List.of("category"),
                    List.of(new SqlBuilder.GroupedAggregation(
                            "amount", "weightedavg", Map.of("weightField", "qty"))),
                    Collections.emptyMap(), null,
                    null, null, 100);

            assertThat(q.sql())
                    .contains("SUM([amount] * [qty])")
                    .contains("NULLIF(SUM(CASE WHEN [amount] IS NOT NULL AND [qty] IS NOT NULL THEN [qty] END), 0)");
        }

        @Test
        void medianWrappedInPartitionByGroupKeys() {
            SqlBuilder.BuiltQuery q = builder.buildGroupedExportQuery(
                    exportDef(), null,
                    List.of("category", "region", "amount"),
                    List.of("category", "region"),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                    Collections.emptyMap(), null,
                    null, null, 100);

            assertThat(q.sql())
                    .contains("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY [amount])")
                    .contains("OVER (PARTITION BY [category], [region])")
                    .contains("MAX([__median_amount]) AS [amount]")
                    .contains("GROUP BY [category], [region]");
        }

        @Test
        void deterministicOrderByAppendsGroupColumns() {
            // sortModel only specifies category DESC; export must append
            // region ASC so two runs over the same input emit identical
            // byte-for-byte output.
            SqlBuilder.BuiltQuery q = builder.buildGroupedExportQuery(
                    exportDef(), null,
                    List.of("category", "region", "amount"),
                    List.of("category", "region"),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(),
                    List.of(Map.of("colId", "category", "sort", "desc")),
                    null, null, 100);

            assertThat(q.sql())
                    .contains("ORDER BY [category] DESC, [region] ASC");
        }
    }

    @Nested
    class BuildPivotedGroupedExportQuery {

        private ReportDefinition pivotExportDef() {
            return new ReportDefinition(
                    "pivot-export", "v1", "PivotExport", "desc", "cat",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text", 100, false),
                            new ColumnDefinition("ba", "Borç/Alacak", "text", 100, false),
                            new ColumnDefinition("amount", "Amount", "number", 120, false)),
                    "category", "ASC",
                    new AccessConfig(null, null, null, null));
        }

        @Test
        void emitsCaseWhenAggsWithoutPagination() {
            SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedExportQuery(
                    pivotExportDef(), null,
                    List.of("category", "ba", "amount"),
                    "category", "ba",
                    List.of(
                            new com.example.report.registry.PivotValue("Borç", "Borç"),
                            new com.example.report.registry.PivotValue("Alacak", "Alacak")),
                    List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                    Collections.emptyMap(), null,
                    null, null, 100);

            assertThat(q.sql())
                    .contains("SELECT TOP(:_maxRows)")
                    .contains("CASE WHEN [ba] = :_pivot_0 THEN [amount]")
                    .contains("CASE WHEN [ba] = :_pivot_1 THEN [amount]")
                    .contains("GROUP BY [category]")
                    .doesNotContain("OFFSET")
                    .doesNotContain("FETCH NEXT");
            assertThat(q.pivotResultFields()).hasSize(2);
            assertThat(q.pivotResultColumns()).hasSize(2);
            assertThat(q.pivotResultColumns().get(0).pivotLabel()).isEqualTo("Borç");
            assertThat(q.pivotResultColumns().get(1).pivotLabel()).isEqualTo("Alacak");
        }

        @Test
        void pivotValuesEmptyRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedExportQuery(
                            pivotExportDef(), null,
                            List.of("category", "ba", "amount"),
                            "category", "ba",
                            List.of(),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                            Collections.emptyMap(), null,
                            null, null, 100));
        }

        @Test
        void medianRejectedInPivotExport() {
            // ALLOWED_PIVOT_AGG_FUNCS excludes median/percentilecont.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.buildPivotedGroupedExportQuery(
                            pivotExportDef(), null,
                            List.of("category", "ba", "amount"),
                            "category", "ba",
                            List.of(new com.example.report.registry.PivotValue("Borç", "Borç")),
                            List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                            Collections.emptyMap(), null,
                            null, null, 100));
        }
    }
}
