package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PR-0.5 (reporting platform hardening, 2026-05) — end-to-end SQL
 * correctness for {@link SqlBuilder} against a real Microsoft SQL
 * Server 2022 instance.
 *
 * <p>The unit tests in {@link SqlBuilderTest} only assert the textual
 * shape of the generated SQL; they cannot catch dialect-specific
 * bugs (e.g. T-SQL square-bracket escaping vs. ANSI quoted identifiers,
 * {@code OFFSET / FETCH NEXT} pagination ordering, {@code GROUP BY}
 * + {@code OVER()} interactions, NULL coercion through
 * {@code ISNULL/NULLIF}). This integration test spins up an MSSQL
 * container, materialises the canonical fact-row schema used by
 * {@code fin-muhasebe-detay} and friends, runs each
 * {@link SqlBuilder} method against it, and asserts on the result
 * set.
 *
 * <p>Gated behind the {@code integration} JUnit tag and
 * {@code @Testcontainers(disabledWithoutDocker = true)} so the default
 * {@code mvn test} stays fast and Docker-free; CI runs this via the
 * {@code -Pintegration-tests} profile.
 *
 * <p>Container reuse: a single class-level container backs every
 * {@code @Test}. Each test creates its own scratch table to keep
 * scenarios independent without paying the ~30s startup cost between
 * methods.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class SqlBuilderMssqlIntegrationTest {

    /**
     * Production-shaped schema name. Workcube tenant schemas follow the
     * pattern {@code workcube_mikrolink_<year>_<companyId>}; using one
     * here exercises the {@link SqlBuilder} {@code [schema].[table]}
     * bracket quoting and the {@code {schema}} placeholder replacement
     * with a real underscore-heavy identifier (Codex iter-1 absorb on
     * PR #88 schema parity finding).
     */
    private static final String TEST_SCHEMA = "workcube_mikrolink_2026_35";

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static SqlBuilder builder;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MSSQL.getDriverClassName());
        ds.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        ds.setUsername(MSSQL.getUsername());
        ds.setPassword(MSSQL.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        builder = new SqlBuilder();

        // Create the production-shaped schema once per container.
        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('" + TEST_SCHEMA + "') IS NULL "
                        + "EXEC('CREATE SCHEMA [" + TEST_SCHEMA + "]')");
    }

    /**
     * Helper: create a temporary scratch table inside the production-
     * shaped {@code TEST_SCHEMA}, seed it with rows, and return a
     * {@link ReportDefinition} pointing at it. Callers pass
     * {@code createSql} / {@code seedSql} with a {@code {schema}}
     * placeholder so the helper substitutes the same identifier that
     * {@link SqlBuilder} will quote in the generated FROM clause.
     */
    private static ReportDefinition scratch(String tableName,
                                              List<ColumnDefinition> columns,
                                              String createSqlTemplate,
                                              List<String> seedSqlTemplates) {
        String fullyQualified = "[" + TEST_SCHEMA + "].[" + tableName + "]";
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + TEST_SCHEMA + "." + tableName + "', 'U') IS NOT NULL "
                        + "DROP TABLE " + fullyQualified);
        jdbc.getJdbcTemplate().execute(
                createSqlTemplate.replace("{schema}", TEST_SCHEMA));
        for (String row : seedSqlTemplates) {
            jdbc.getJdbcTemplate().execute(row.replace("{schema}", TEST_SCHEMA));
        }
        return new ReportDefinition(
                "scratch-" + tableName,
                "1",
                "Scratch " + tableName,
                "test",
                "test",
                tableName,
                TEST_SCHEMA,
                "static",
                null,
                null,
                columns,
                null,
                "ASC",
                new AccessConfig(null, null, null, null));
    }

    @Test
    void buildDataQuery_pagesAndOrders_overRealMssql() {
        ReportDefinition def = scratch(
                "tx",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx] (id INT NOT NULL PRIMARY KEY, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx] VALUES (1, 100.00)",
                        "INSERT INTO [{schema}].[tx] VALUES (2, 200.00)",
                        "INSERT INTO [{schema}].[tx] VALUES (3, 300.00)",
                        "INSERT INTO [{schema}].[tx] VALUES (4, 400.00)",
                        "INSERT INTO [{schema}].[tx] VALUES (5, 500.00)"));

        // Page 2, size 2 → rows 3, 4 with default ORDER BY (SELECT NULL).
        // We pass an explicit sort to make the assertion deterministic.
        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                null, null, 2, 2);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id")).isEqualTo(3);
        assertThat(rows.get(1).get("id")).isEqualTo(4);
        // DECIMAL(18,2) MUST round-trip through JDBC as BigDecimal — this
        // proves the SqlBuilder pipeline is contract-stable for the
        // currency columns the report layer relies on (Codex iter-1
        // absorb on PR #88 precision contract finding).
        assertThat(rows.get(0).get("amount")).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) rows.get(0).get("amount")))
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void buildCountQuery_returnsRowCount_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_count",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                "CREATE TABLE [{schema}].[tx_count] (id INT NOT NULL PRIMARY KEY)",
                List.of(
                        "INSERT INTO [{schema}].[tx_count] VALUES (1)",
                        "INSERT INTO [{schema}].[tx_count] VALUES (2)",
                        "INSERT INTO [{schema}].[tx_count] VALUES (3)"));

        SqlBuilder.BuiltQuery q = builder.buildCountQuery(
                def, null, Collections.emptyMap(), List.of("id"), null, null);

        Long count = jdbc.queryForObject(q.sql(), q.params(), Long.class);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void buildGroupedQuery_singleLevelSum_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_grouped",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_grouped] (category NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_grouped] VALUES ('FIN', 100.00)",
                        "INSERT INTO [{schema}].[tx_grouped] VALUES ('FIN', 50.00)",
                        "INSERT INTO [{schema}].[tx_grouped] VALUES ('FIN', 25.00)",
                        "INSERT INTO [{schema}].[tx_grouped] VALUES ('HR', 1000.00)",
                        "INSERT INTO [{schema}].[tx_grouped] VALUES ('HR', 500.00)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        // ORDER BY [category] ASC by default → FIN first, HR second.
        assertThat(rows.get(0).get("category")).isEqualTo("FIN");
        assertThat(((Number) rows.get(0).get("_rowCount")).longValue()).isEqualTo(3L);
        assertThat(rows.get(0).get("amount")).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) rows.get(0).get("amount")))
                .isEqualByComparingTo(new BigDecimal("175.00"));
        assertThat(rows.get(1).get("category")).isEqualTo("HR");
        assertThat(((Number) rows.get(1).get("_rowCount")).longValue()).isEqualTo(2L);
        assertThat(((BigDecimal) rows.get(1).get("amount")))
                .isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    @Test
    void buildGroupedQuery_avgMinMax_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_aggs",
                List.of(
                        new ColumnDefinition("region", "R", "text", 100, false),
                        new ColumnDefinition("amount", "A", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_aggs] (region NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_aggs] VALUES ('EU', 100.00)",
                        "INSERT INTO [{schema}].[tx_aggs] VALUES ('EU', 200.00)",
                        "INSERT INTO [{schema}].[tx_aggs] VALUES ('EU', 300.00)"));

        // AVG branch.
        SqlBuilder.BuiltQuery avgQ = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "avg")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> avgRows = jdbc.queryForList(avgQ.sql(), avgQ.params());
        assertThat(avgRows).hasSize(1);
        assertThat(avgRows.get(0).get("amount")).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) avgRows.get(0).get("amount")))
                .isEqualByComparingTo(new BigDecimal("200.00"));

        // MIN branch — Codex iter-1 absorb on PR #88 aggregation breadth
        // finding (avg alone leaves min/max as silent surface).
        SqlBuilder.BuiltQuery minQ = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "min")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> minRows = jdbc.queryForList(minQ.sql(), minQ.params());
        assertThat(minRows).hasSize(1);
        assertThat(((BigDecimal) minRows.get(0).get("amount")))
                .isEqualByComparingTo(new BigDecimal("100.00"));

        // MAX branch.
        SqlBuilder.BuiltQuery maxQ = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "max")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> maxRows = jdbc.queryForList(maxQ.sql(), maxQ.params());
        assertThat(maxRows).hasSize(1);
        assertThat(((BigDecimal) maxRows.get(0).get("amount")))
                .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void buildGroupedCountQuery_returnsBucketCount_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_bucket",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_bucket] (category NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_bucket] VALUES ('FIN', 100.00)",
                        "INSERT INTO [{schema}].[tx_bucket] VALUES ('FIN', 50.00)",
                        "INSERT INTO [{schema}].[tx_bucket] VALUES ('HR', 1000.00)",
                        "INSERT INTO [{schema}].[tx_bucket] VALUES ('OPS', 5.00)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedCountQuery(
                def, null, List.of("category", "amount"), "category",
                Collections.emptyMap(), null, null);

        // 3 distinct categories regardless of source row count.
        Long count = jdbc.queryForObject(q.sql(), q.params(), Long.class);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void buildGroupedQuery_filtersOutNullsViaIsnullSentinel() {
        // Mirrors PR #86 source-query pattern: the production SQL
        // wraps LEFT JOIN'd dimension labels in
        // ISNULL(NULLIF(LTRIM(RTRIM(x)), ''), 'Belirtilmemiş') so
        // null buckets collapse into a single sentinel value the
        // expansion path can handle. Verify the same behavior on a
        // scratch table with a custom sourceQuery using the
        // {schema} placeholder (production parity — fin-muhasebe-detay
        // and friends use the same pattern).
        //
        // The 'Belirtilmemiş' literal is N-prefixed so MSSQL treats it
        // as NVARCHAR; without the N prefix the default Latin1 code
        // page (CI Testcontainers default collation
        // SQL_Latin1_General_CP1_CI_AS) silently strips the 'ş' to
        // 's', breaking the assertion. Production Workcube DBs run
        // Turkish_CI_AS so they happen to round-trip; we want the
        // test deterministic across collations.
        String tableName = "tx_null";
        String fullyQualified = "[" + TEST_SCHEMA + "].[" + tableName + "]";
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + TEST_SCHEMA + "." + tableName + "', 'U') IS NOT NULL "
                        + "DROP TABLE " + fullyQualified);
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE " + fullyQualified + " (id INT NOT NULL PRIMARY KEY, "
                        + "department NVARCHAR(20) NULL, amount DECIMAL(18,2) NOT NULL)");
        jdbc.getJdbcTemplate().execute("INSERT INTO " + fullyQualified + " VALUES (1, N'FIN', 100.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO " + fullyQualified + " VALUES (2, NULL,  50.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO " + fullyQualified + " VALUES (3, '',    25.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO " + fullyQualified + " VALUES (4, '   ', 10.00)");

        ReportDefinition def = new ReportDefinition(
                "scratch-tx_null",
                "1",
                "Scratch null sentinel",
                "test",
                "test",
                null,
                TEST_SCHEMA,
                "static",
                null,
                "SELECT id, ISNULL(NULLIF(LTRIM(RTRIM(department)), ''), N'Belirtilmemiş') AS department, amount FROM [{schema}].[tx_null]",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("department", "Dept", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("id", "department", "amount"),
                "department",
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        // The 3 NULL/empty/whitespace rows collapse into one
        // 'Belirtilmemiş' bucket; FIN keeps its own.
        assertThat(rows).anyMatch(r ->
                "Belirtilmemiş".equals(r.get("department"))
                        && ((Number) r.get("_rowCount")).longValue() == 3L
                        && ((BigDecimal) r.get("amount")).compareTo(new BigDecimal("85.00")) == 0);
        assertThat(rows).anyMatch(r ->
                "FIN".equals(r.get("department"))
                        && ((Number) r.get("_rowCount")).longValue() == 1L
                        && ((BigDecimal) r.get("amount")).compareTo(new BigDecimal("100.00")) == 0);
    }

    @Test
    void buildGroupedQuery_unknownGroupColumn_rejected() {
        ReportDefinition def = scratch(
                "tx_reject",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                "CREATE TABLE [{schema}].[tx_reject] (id INT NOT NULL PRIMARY KEY)",
                List.of("INSERT INTO [{schema}].[tx_reject] VALUES (1)"));

        // builder validates against the visible-column allowlist before
        // hitting the DB; we still cover this as an integration-level
        // contract because future refactors might move the check.
        assertThatThrownBy(() -> builder.buildGroupedQuery(
                def, null, List.of("id"),
                "ghost", List.of(),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildExportQuery_topRowsAndOrder_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_export",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_export] (id INT NOT NULL PRIMARY KEY, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_export] VALUES (1, 10.00)",
                        "INSERT INTO [{schema}].[tx_export] VALUES (2, 20.00)",
                        "INSERT INTO [{schema}].[tx_export] VALUES (3, 30.00)",
                        "INSERT INTO [{schema}].[tx_export] VALUES (4, 40.00)",
                        "INSERT INTO [{schema}].[tx_export] VALUES (5, 50.00)"));

        SqlBuilder.BuiltQuery q = builder.buildExportQuery(
                def, null, List.of("id", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "desc")),
                null, null, 3);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(3);
        // ORDER BY id DESC + TOP 3 → 5, 4, 3.
        assertThat(rows.get(0).get("id")).isEqualTo(5);
        assertThat(rows.get(1).get("id")).isEqualTo(4);
        assertThat(rows.get(2).get("id")).isEqualTo(3);
    }

    @Test
    void buildDataQuery_filterModelEqualsAndContains_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_filter",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("name", "Name", "text", 100, false)),
                "CREATE TABLE [{schema}].[tx_filter] (id INT NOT NULL PRIMARY KEY, name NVARCHAR(50) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_filter] VALUES (1, 'alpha')",
                        "INSERT INTO [{schema}].[tx_filter] VALUES (2, 'beta')",
                        "INSERT INTO [{schema}].[tx_filter] VALUES (3, 'alphabet')"));

        // FilterTranslator's "contains" should produce LIKE '%alpha%'
        // which on T-SQL with the default collation matches both
        // 'alpha' and 'alphabet'.
        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "name"),
                Map.of("name", Map.of("filterType", "text", "type", "contains", "filter", "alpha")),
                List.of(Map.of("colId", "id", "sort", "asc")),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name")).isEqualTo("alpha");
        assertThat(rows.get(1).get("name")).isEqualTo("alphabet");
    }
}
