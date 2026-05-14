package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.PivotValue;
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

    /**
     * PR-0.4z (Codex 019e2695 review iter-2) — end-to-end dialect proof
     * for the three new aggregate mappings against a real MSSQL 2022
     * instance.
     *
     * <ul>
     *   <li>{@code distinctcount} → {@code COUNT(DISTINCT [user_id])}</li>
     *   <li>{@code stddev} → {@code STDEV([amount])} sample, n-1 denom</li>
     *   <li>{@code stddevp} → {@code STDEVP([amount])} population, n denom</li>
     * </ul>
     *
     * <p>Fixture: amounts 10/20/30/40 (avg = 25, Σ(x-μ)² = 500). Expected
     * sample variance = 500/3 ≈ 166.667 → STDEV ≈ 12.910; population
     * variance = 500/4 = 125 → STDEVP ≈ 11.180. Asserted with a 0.01
     * tolerance to absorb MSSQL float rounding.
     */
    @Test
    void buildGroupedQuery_distinctCountStddevStddevp_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_pr04z",
                List.of(
                        new ColumnDefinition("region", "R", "text", 100, false),
                        new ColumnDefinition("user_id", "U", "number", 100, false),
                        new ColumnDefinition("amount", "A", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_pr04z] "
                        + "(region NVARCHAR(20) NOT NULL, user_id INT NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_pr04z] VALUES ('EU', 1, 10.00)",
                        "INSERT INTO [{schema}].[tx_pr04z] VALUES ('EU', 2, 20.00)",
                        "INSERT INTO [{schema}].[tx_pr04z] VALUES ('EU', 2, 30.00)",
                        "INSERT INTO [{schema}].[tx_pr04z] VALUES ('EU', 3, 40.00)"));

        // distinctcount → 3 (user_ids 1, 2, 3)
        SqlBuilder.BuiltQuery dcQ = builder.buildGroupedQuery(
                def, null, List.of("region", "user_id"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("user_id", "distinctcount")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> dcRows = jdbc.queryForList(dcQ.sql(), dcQ.params());
        assertThat(dcRows).hasSize(1);
        assertThat(((Number) dcRows.get(0).get("user_id")).intValue()).isEqualTo(3);
        assertThat(dcQ.sql()).contains("COUNT(DISTINCT [user_id])");

        // stddev (sample) → √(500/3) ≈ 12.910
        SqlBuilder.BuiltQuery sdQ = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "stddev")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> sdRows = jdbc.queryForList(sdQ.sql(), sdQ.params());
        assertThat(sdRows).hasSize(1);
        assertThat(((Number) sdRows.get(0).get("amount")).doubleValue())
                .isCloseTo(12.910, within(0.01));
        assertThat(sdQ.sql()).contains("STDEV([amount])");

        // stddevp (population) → √125 ≈ 11.180
        SqlBuilder.BuiltQuery sdpQ = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "stddevp")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> sdpRows = jdbc.queryForList(sdpQ.sql(), sdpQ.params());
        assertThat(sdpRows).hasSize(1);
        assertThat(((Number) sdpRows.get(0).get("amount")).doubleValue())
                .isCloseTo(11.180, within(0.01));
        assertThat(sdpQ.sql()).contains("STDEVP([amount])");
    }

    /**
     * PR #6a (Codex 019e2695) median execution proof against a real
     * MSSQL 2022 instance. Three sub-cases share a single scratch
     * table:
     *
     * <ul>
     *   <li>Odd group (3 rows: 3, 6, 9) → median = 6 — exact value.</li>
     *   <li>Even group (4 rows: 1, 2, 3, 4) → median = 2.5 —
     *       interpolated midpoint of the two middle values, the
     *       canonical PERCENTILE_CONT semantic.</li>
     *   <li>Group with a NULL row → PERCENTILE_CONT ignores the NULL
     *       in the percentile calculation, but the outer COUNT(*)
     *       still counts every row including the NULL.</li>
     * </ul>
     *
     * <p>MSSQL PERCENTILE_CONT returns {@code float(53)}, so the
     * assertions use {@code within(0.0001)} to absorb the float
     * round-trip without losing the median's semantic precision.
     */
    @Test
    void buildGroupedQuery_medianAggregation_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_median",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_median] "
                        + "(category NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NULL)",
                List.of(
                        // Odd group → median = 6
                        "INSERT INTO [{schema}].[tx_median] VALUES ('ODD', 3.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('ODD', 6.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('ODD', 9.00)",
                        // Even group → median = 2.5
                        "INSERT INTO [{schema}].[tx_median] VALUES ('EVEN', 1.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('EVEN', 2.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('EVEN', 3.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('EVEN', 4.00)",
                        // NULL row in NULLY group → PERCENTILE_CONT skips
                        // null; remaining 10, 20 → median = 15
                        "INSERT INTO [{schema}].[tx_median] VALUES ('NULLY', 10.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('NULLY', 20.00)",
                        "INSERT INTO [{schema}].[tx_median] VALUES ('NULLY', NULL)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation("amount", "median")),
                Collections.emptyMap(),
                List.of(Map.of("colId", "category", "sort", "asc")),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(3);

        // The outer SELECT exposes the median under the external
        // alias [amount] — no [amount_median] suffix.
        Map<String, Object> evenRow = rows.stream()
                .filter(r -> "EVEN".equals(r.get("category"))).findFirst().orElseThrow();
        Map<String, Object> oddRow = rows.stream()
                .filter(r -> "ODD".equals(r.get("category"))).findFirst().orElseThrow();
        Map<String, Object> nullyRow = rows.stream()
                .filter(r -> "NULLY".equals(r.get("category"))).findFirst().orElseThrow();

        assertThat(((Number) evenRow.get("amount")).doubleValue())
                .isCloseTo(2.5, within(0.0001));
        assertThat(((Number) oddRow.get("amount")).doubleValue())
                .isCloseTo(6.0, within(0.0001));
        // NULLY group has 3 rows total (one is NULL) — PERCENTILE_CONT
        // ignores the null in median calc but _rowCount still counts it.
        assertThat(((Number) nullyRow.get("amount")).doubleValue())
                .isCloseTo(15.0, within(0.0001));
        assertThat(((Number) nullyRow.get("_rowCount")).intValue())
                .isEqualTo(3);
    }

    /**
     * PR #6b (Codex 019e2695) percentilecont execution proof against
     * a real MSSQL 2022 instance. Three percentile ranks share a
     * single 5-row fixture: values 10/20/30/40/50, group=ALL.
     *
     * <ul>
     *   <li>{@code p=0.0} → 10.0 (min)</li>
     *   <li>{@code p=0.9} → 46.0 (continuous interpolation:
     *       {@code 1 + 0.9 * (5 - 1) = 4.6}, between values at
     *       ranks 4 and 5 → {@code 40 + 0.6 * (50 - 40) = 46})</li>
     *   <li>{@code p=1.0} → 50.0 (max)</li>
     * </ul>
     *
     * <p>{@code within(0.0001)} absorbs the {@code float(53)} round-trip
     * without losing the percentile's semantic precision.
     */
    @Test
    void buildGroupedQuery_percentileContAggregation_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_pctcont",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[tx_pctcont] "
                        + "(category NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[tx_pctcont] VALUES ('ALL', 10.00)",
                        "INSERT INTO [{schema}].[tx_pctcont] VALUES ('ALL', 20.00)",
                        "INSERT INTO [{schema}].[tx_pctcont] VALUES ('ALL', 30.00)",
                        "INSERT INTO [{schema}].[tx_pctcont] VALUES ('ALL', 40.00)",
                        "INSERT INTO [{schema}].[tx_pctcont] VALUES ('ALL', 50.00)"));

        // p=0.9 → 46.0 (Codex spec exact value)
        SqlBuilder.BuiltQuery q90 = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation(
                        "amount", "percentilecont",
                        Map.of("percentile", 0.9))),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> r90 = jdbc.queryForList(q90.sql(), q90.params());
        assertThat(r90).hasSize(1);
        assertThat(((Number) r90.get(0).get("amount")).doubleValue())
                .isCloseTo(46.0, within(0.0001));

        // p=0.0 → 10.0 (min)
        SqlBuilder.BuiltQuery q0 = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation(
                        "amount", "percentilecont",
                        Map.of("percentile", 0.0))),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> r0 = jdbc.queryForList(q0.sql(), q0.params());
        assertThat(((Number) r0.get(0).get("amount")).doubleValue())
                .isCloseTo(10.0, within(0.0001));

        // p=1.0 → 50.0 (max)
        SqlBuilder.BuiltQuery q1 = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation(
                        "amount", "percentilecont",
                        Map.of("percentile", 1.0))),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);
        List<Map<String, Object>> r1 = jdbc.queryForList(q1.sql(), q1.params());
        assertThat(((Number) r1.get(0).get("amount")).doubleValue())
                .isCloseTo(50.0, within(0.0001));
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

    // ── PR-0.4b: pivot SQL correctness against MSSQL ─────────────

    @Test
    void buildPivotedGroupedQuery_sumBucketsAggregateOverRealMssql() {
        ReportDefinition def = scratch(
                "pvt_sum",
                List.of(
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_sum] ("
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[pvt_sum] VALUES ('FIN', 'A', 100.00)",
                        "INSERT INTO [{schema}].[pvt_sum] VALUES ('FIN', 'A', 50.00)",
                        "INSERT INTO [{schema}].[pvt_sum] VALUES ('FIN', 'P', 30.00)",
                        "INSERT INTO [{schema}].[pvt_sum] VALUES ('HR',  'A', 200.00)",
                        "INSERT INTO [{schema}].[pvt_sum] VALUES ('HR',  'P', 25.00)"));

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null, List.of("category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(),
                List.of(Map.of("colId", "category", "sort", "asc")),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        // 2 group buckets emitted in deterministic ASC order.
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("category")).isEqualTo("FIN");
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__A__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__P__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(rows.get(1).get("category")).isEqualTo("HR");
        assertThat(((BigDecimal) rows.get(1).get("pvt__status__A__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(((BigDecimal) rows.get(1).get("pvt__status__P__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void buildPivotedGroupedQuery_avgDenominatorOmitsOutOfBucketRows() {
        // Critical correctness invariant (Codex Q5 verdict): AVG over a
        // pivot must NOT include out-of-bucket rows as zero. With
        // category=FIN we have status=A rows (100, 50) and status=P
        // rows (30). The AVG for bucket A must be 75 (mean of 100,50),
        // NOT (100+50+0)/3 = 50. The renderer's `ELSE NULL` branch is
        // what keeps the denominator honest.
        ReportDefinition def = scratch(
                "pvt_avg",
                List.of(
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_avg] ("
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[pvt_avg] VALUES ('FIN', 'A', 100.00)",
                        "INSERT INTO [{schema}].[pvt_avg] VALUES ('FIN', 'A', 50.00)",
                        "INSERT INTO [{schema}].[pvt_avg] VALUES ('FIN', 'P', 30.00)"));

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null, List.of("category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "avg")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__A__avg__amount")))
                // 75 = (100 + 50) / 2; "P" rows are NOT pulled into A's
                // denominator. Tolerance accommodates MSSQL's DECIMAL → AVG
                // rounding (returns DECIMAL(38,6) here).
                .isCloseTo(new BigDecimal("75.000000"),
                        within(new BigDecimal("0.000001")));
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__P__avg__amount")))
                .isCloseTo(new BigDecimal("30.000000"),
                        within(new BigDecimal("0.000001")));
    }

    @Test
    void buildPivotedGroupedQuery_countOmitsOutOfBucketNullsOnRealMssql() {
        // COUNT(CASE WHEN p THEN field END) — out-of-bucket rows collapse
        // to NULL and MSSQL's COUNT drops them. The legacy `COUNT([field])`
        // semantic (non-null count) is preserved.
        ReportDefinition def = scratch(
                "pvt_count",
                List.of(
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_count] ("
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[pvt_count] VALUES ('FIN', 'A', 100.00)",
                        "INSERT INTO [{schema}].[pvt_count] VALUES ('FIN', 'A', 50.00)",
                        "INSERT INTO [{schema}].[pvt_count] VALUES ('FIN', 'P', 30.00)"));

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null, List.of("category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "count")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        // 2 rows in status=A bucket → count = 2; not 3 (would include the P row).
        assertThat(rows.get(0).get("pvt__status__A__count__amount")).isEqualTo(2);
        assertThat(rows.get(0).get("pvt__status__P__count__amount")).isEqualTo(1);
    }

    @Test
    void buildPivotedGroupedQuery_rlsScopeKeepsOtherTenantsOutOfBuckets() {
        // RLS clause runs inside the FROM-clause WHERE, BEFORE pivot
        // aggregation. The other tenant's rows must not leak into the
        // scoped tenant's pivot buckets.
        ReportDefinition def = scratch(
                "pvt_rls",
                List.of(
                        new ColumnDefinition("owner_id", "Owner", "number", 50, false),
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_rls] ("
                        + "owner_id INT NOT NULL, "
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[pvt_rls] VALUES (100, 'FIN', 'A', 10.00)",
                        "INSERT INTO [{schema}].[pvt_rls] VALUES (100, 'FIN', 'P', 20.00)",
                        // owner 200's row would be massive enough to dwarf
                        // owner 100's bucket; RLS MUST keep it out.
                        "INSERT INTO [{schema}].[pvt_rls] VALUES (200, 'FIN', 'A', 9999.00)",
                        "INSERT INTO [{schema}].[pvt_rls] VALUES (200, 'FIN', 'P', 8888.00)"));

        // Owner 100 scope.
        org.springframework.jdbc.core.namedparam.MapSqlParameterSource rlsParams =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
        rlsParams.addValue("_rls_owner", 100);
        String rlsWhere = "[owner_id] = :_rls_owner";

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null,
                List.of("owner_id", "category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                rlsWhere, rlsParams, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__A__sum__amount")))
                // owner 200's 9999 row stayed OUT of the bucket aggregation.
                .isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__P__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    void buildPivotedGroupedQuery_filterModelComposesWithPivot() {
        // User-supplied filterModel runs alongside RLS in the FROM
        // clause, BEFORE the pivot aggregation. The pivot buckets must
        // see only the filtered rowset.
        ReportDefinition def = scratch(
                "pvt_filter",
                List.of(
                        new ColumnDefinition("region", "Region", "text", 60, false),
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_filter] ("
                        + "region NVARCHAR(20) NOT NULL, "
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO [{schema}].[pvt_filter] VALUES ('IST', 'FIN', 'A', 100.00)",
                        "INSERT INTO [{schema}].[pvt_filter] VALUES ('IST', 'FIN', 'P', 50.00)",
                        "INSERT INTO [{schema}].[pvt_filter] VALUES ('ANK', 'FIN', 'A', 999.00)"));

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null,
                List.of("region", "category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Map.of("region", Map.of("type", "equals", "filter", "IST")),
                Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__A__sum__amount")))
                // ANK's 999 must NOT leak into the bucket; filter ran
                // before pivot aggregation.
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__P__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void buildPivotedGroupedQuery_emptyBucketSumProducesZero() {
        // SUM uses `ELSE 0` so an empty bucket reads as 0 rather than
        // NULL. Finance grids prefer the zero cell so column totals
        // line up cleanly.
        ReportDefinition def = scratch(
                "pvt_empty",
                List.of(
                        new ColumnDefinition("category", "Category", "text", 80, false),
                        new ColumnDefinition("status", "Status", "text", 60, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE [{schema}].[pvt_empty] ("
                        + "category NVARCHAR(20) NOT NULL, "
                        + "status NVARCHAR(20) NOT NULL, "
                        + "amount DECIMAL(18,2) NOT NULL)",
                // Only one bucket populated; pivot value "P" is empty.
                List.of("INSERT INTO [{schema}].[pvt_empty] VALUES ('FIN', 'A', 100.00)"));

        SqlBuilder.PivotedBuiltQuery q = builder.buildPivotedGroupedQuery(
                def, null, List.of("category", "status", "amount"),
                "category", "status",
                List.of(new PivotValue("A"), new PivotValue("P")),
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__A__sum__amount")))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(((BigDecimal) rows.get(0).get("pvt__status__P__sum__amount")))
                // SUM(CASE … ELSE 0 END) over an empty bucket evaluates
                // to 0, not NULL — the legacy finance convention.
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
