package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
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
                    new YearlySchemaResolver.ResolvedSchemas(List.of("db_2023", "db_2024"));

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
                    new YearlySchemaResolver.ResolvedSchemas(List.of("db_2023", "db_2024"));

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
                    new YearlySchemaResolver.ResolvedSchemas(List.of("y2023", "y2024"));

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
        void invalidAggregationFunctionRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new SqlBuilder.GroupedAggregation("col", "median"));
        }

        @Test
        void blankAggregationFieldRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new SqlBuilder.GroupedAggregation("", "sum"));
        }
    }
}
