package com.example.report.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.ScopeSummaryDto;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * PR #3 (SSRM hardening sprint, Codex thread 019e2695) — end-to-end
 * proof for the RLS injection chain against a real Microsoft SQL
 * Server 2022 instance.
 *
 * <p>The chain wired by this harness mirrors production exactly:
 * <pre>
 *   AuthzMeResponse (fixture)
 *     → RowFilterInjector.buildRlsClause(def, authz)
 *     → SqlBuilder.buildDataQuery / buildGroupedQuery (rls clause + params)
 *     → NamedParameterJdbcTemplate.query (MSSQL execute)
 *     → row assertions
 * </pre>
 *
 * <p>{@link RowFilterInjectorRlsTest} is the unit-level counterpart —
 * it asserts the {@link RowFilterInjector.RlsResult} shape but does
 * not push the clause through {@link SqlBuilder} or execute against a
 * database, so a regression that drops the RLS append from
 * {@code buildFromClause} or mis-binds {@code _rlsIds} would slip
 * past unit coverage. This IT closes that gap.
 *
 * <p>Gated behind the {@code integration} JUnit tag and
 * {@code @Testcontainers(disabledWithoutDocker = true)} so the
 * default {@code mvn test} run on developer machines without Docker
 * still passes. CI runs this class through the existing report-service
 * MSSQL Testcontainers integration job.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class RlsExecutionMssqlIntegrationTest {

    private static final String TEST_SCHEMA = "workcube_mikrolink_rls_2026";

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static SqlBuilder builder;
    private static RowFilterInjector injector;
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
        injector = new RowFilterInjector();

        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('" + TEST_SCHEMA + "') IS NULL "
                        + "EXEC('CREATE SCHEMA [" + TEST_SCHEMA + "]')");

        // Multi-tenant fact table: 3 owners × 2 rows each = 6 rows total.
        // Each test executes against this shared fixture so the RLS
        // filtering shows up as a row-count delta rather than fixture noise.
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + TEST_SCHEMA + ".tx_rls', 'U') IS NOT NULL "
                        + "DROP TABLE [" + TEST_SCHEMA + "].[tx_rls]");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE [" + TEST_SCHEMA + "].[tx_rls] ("
                        + "id INT NOT NULL PRIMARY KEY, "
                        + "owner_id INT NOT NULL, "
                        + "category NVARCHAR(20) NULL, "  // PR #4: nullable so the IS NULL bucket test can seed a null row
                        + "amount DECIMAL(18,2) NOT NULL)");
        List<String> seeds = List.of(
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (1, 100, 'FIN', 10.00)",
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (2, 100, 'FIN', 20.00)",
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (3, 200, 'HR',  30.00)",
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (4, 200, 'HR',  40.00)",
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (5, 300, 'OPS', 50.00)",
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] VALUES (6, 300, 'OPS', 60.00)");
        for (String row : seeds) {
            jdbc.getJdbcTemplate().execute(row);
        }
    }

    /** Build a {@link ReportDefinition} with a COMPANY-scoped row filter. */
    private static ReportDefinition rlsDef() {
        AccessConfig.RowFilter rf = new AccessConfig.RowFilter(
                "owner_id", "COMPANY", "REPORT_ADMIN_BYPASS");
        AccessConfig access = new AccessConfig("REPORT_VIEW", "RLS_IT_REPORTS", Map.of(), rf);
        return new ReportDefinition(
                "rls-it-report", "1.0", "RLS IT Report", "test", "test",
                "tx_rls", TEST_SCHEMA, "static", null, null,
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("owner_id", "Owner", "number", 80, false),
                        new ColumnDefinition("category", "Cat", "text", 100, false,
                                true, false, null),
                        new ColumnDefinition("amount", "Amount", "number", 120, false,
                                false, true, "sum")),
                "id", "ASC", access);
    }

    /** Build an {@link AuthzMeResponse} for a non-admin user with the
     *  given COMPANY scope refs. */
    private static AuthzMeResponse companyScopedUser(String... companyIds) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("user-rls-it");
        authz.setSuperAdmin(false);
        authz.setPermissions(List.of("REPORT_VIEW"));
        authz.setAllowedScopes(
                java.util.Arrays.stream(companyIds)
                        .map(id -> new ScopeSummaryDto("COMPANY", id))
                        .toList());
        return authz;
    }

    private static AuthzMeResponse superAdmin() {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("admin-rls-it");
        authz.setSuperAdmin(true);
        authz.setPermissions(List.of("REPORT_VIEW"));
        authz.setAllowedScopes(List.of());
        return authz;
    }

    private static AuthzMeResponse bypassUser() {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("bypass-rls-it");
        authz.setSuperAdmin(false);
        authz.setPermissions(List.of("REPORT_VIEW", "REPORT_ADMIN_BYPASS"));
        authz.setAllowedScopes(List.of());
        return authz;
    }

    @Test
    void rlsScopedUser_seesOnlyAllowedCompanyRows() {
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = companyScopedUser("100", "200");

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);
        assertThat(rls.whereClause()).isEqualTo("[owner_id] IN (:_rlsIds)");

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        // 4 rows: ids 1..4 (owner 100 + owner 200). Owner 300 (ids 5, 6) hidden.
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.get("owner_id"))
                .containsOnly(100, 200)
                .doesNotContain(300);
    }

    @Test
    void rlsSuperAdminNoExplicitScope_seesAllRows() {
        // Legacy unrestricted-admin path: super-admin without explicit
        // scope of the relevant axis bypasses the RLS clause.
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = superAdmin();

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);
        assertThat(rls.whereClause()).isNull();

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        assertThat(rows).hasSize(6);
    }

    @Test
    void rlsEmptyScopeUser_seesZeroRows() {
        // Codex 019dfc41 iter-4 absorb: a non-admin user with an empty
        // COMPANY scope must receive a hard-deny (1=0) clause so the
        // query returns nothing rather than falling through to a
        // permissive WHERE 1=1.
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = companyScopedUser(); // no companies

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);
        assertThat(rls.whereClause()).isEqualTo("1=0");

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        assertThat(rows).isEmpty();
    }

    @Test
    void rlsBypassPermission_seesAllRows() {
        // Holders of the rowFilter.bypassPermission see the full set
        // even though their scope list is empty.
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = bypassUser();

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);
        assertThat(rls.whereClause()).isNull();

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        assertThat(rows).hasSize(6);
    }

    /**
     * PR #4 (Codex 019e2695) cross-feature proof: RLS + nullable
     * group column. The fixture is seeded with one row whose
     * {@code category} is {@code NULL}; a scoped user expanding the
     * "(Blanks)" bucket should see only that row, and only when their
     * RLS scope permits the owner.
     */
    @Test
    void rlsCombinesWithNullGroupBucketExpansion() {
        // Add one NULL-category row to the shared fixture; isolation
        // is preserved because the row is owned by owner 100 (already
        // visible to the scoped fixture) and read-only.
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO [" + TEST_SCHEMA + "].[tx_rls] "
                        + "(id, owner_id, category, amount) VALUES (7, 100, NULL, 99.00)");

        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = companyScopedUser("100", "200");

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);

        // Build a filter that exactly matches what mergeAncestorFilters
        // emits for a null groupKey: {category: {type: blank}}. The
        // SqlBuilder receives the agGridFilter and pushes it through
        // FilterTranslator → IS NULL.
        java.util.Map<String, Object> nullBucketFilter = java.util.Map.of(
                "category", java.util.Map.of("type", "blank"));

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                nullBucketFilter,
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        // Expected SQL composition: RLS + IS NULL on category.
        assertThat(q.sql())
                .contains("[owner_id] IN (:_rlsIds)")
                .contains("[category] IS NULL");

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        // Only the new null-category row matches both predicates
        // (owner 100 ∈ scope, category IS NULL).
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(7);
        assertThat(rows.get(0).get("category")).isNull();

    }

    /**
     * Codex 019e2695 review iter-3 absorb: the null-bucket scenario
     * is the first mutating case in an otherwise read-only shared
     * fixture. {@link AfterEach} keeps the canonical 6-row baseline
     * intact unconditionally — including the case where the test
     * body fails before reaching its inline cleanup, which would
     * otherwise cascade into the next test.
     */
    @AfterEach
    void resetMutatingFixtureRow() {
        jdbc.getJdbcTemplate().execute(
                "DELETE FROM [" + TEST_SCHEMA + "].[tx_rls] WHERE id = 7");
    }

    /**
     * PR #5b (Codex 019e2695) cross-feature proof: RLS + the PR #5a
     * compound parser, executed end-to-end. We hand FilterTranslator
     * the exact shape that PR #5b's mergeAncestorFilters now emits
     * when an ancestor + user filter collide — an AND compound with
     * {@code condition1} = ancestor equals, {@code condition2} = user
     * contains — and verify the SQL contains both predicates joined
     * with AND inside parentheses, plus the row count is 2 (both
     * FIN-category rows owned by owner 100 satisfy both predicates).
     *
     * <p>Composes with the RLS scope: owner 100 + 200 are visible,
     * so the two FIN rows from owner 100 (ids 1, 2) survive the
     * RLS clause; owner 200 (HR) is filtered out by the category
     * predicate, owner 300 (OPS) is filtered out by RLS.
     */
    @Test
    void rlsCombinesWithCompoundAncestorAndUserFilter() {
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = companyScopedUser("100", "200");

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);

        java.util.Map<String, Object> compound = java.util.Map.of(
                "operator", "AND",
                "condition1", java.util.Map.of("type", "equals", "filter", "FIN"),
                "condition2", java.util.Map.of("type", "contains", "filter", "FI"));
        java.util.Map<String, Object> filterModel = java.util.Map.of("category", compound);

        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                filterModel,
                List.of(Map.of("colId", "id", "sort", "asc")),
                rls.whereClause(), rls.params(), 1, 100);

        // SQL composition: RLS + compound AND on category (parens
        // around both equals + LIKE) + tie-breaker semantics not
        // applicable to buildDataQuery (no GROUP BY).
        assertThat(q.sql())
                .contains("[owner_id] IN (:_rlsIds)")
                .contains("[category] = :")
                .contains("[category] LIKE :");

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        // Owner 100 has 2 FIN rows (ids 1, 2). Owner 200 has HR rows
        // (excluded by category=FIN). Owner 300 excluded by RLS.
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("category"))
                .containsOnly("FIN");
        assertThat(rows).extracting(r -> r.get("owner_id"))
                .containsOnly(100);
    }

    @Test
    void rlsCombinesWithGroupedQueryAndTieBreaker() {
        // Cross-PR proof: RLS + grouped aggregation + the PR #2
        // tie-breaker discipline all compose. A scoped user grouping
        // by category sees aggregates for only the categories
        // attached to their allowed owners, and the OFFSET/FETCH
        // ORDER BY chain ends in [category] ASC for deterministic
        // pagination.
        ReportDefinition def = rlsDef();
        AuthzMeResponse authz = companyScopedUser("100", "200");

        RowFilterInjector.RlsResult rls = injector.buildRlsClause(def, authz);

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("id", "owner_id", "category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(),
                List.of(Map.of("colId", "amount", "sort", "desc")),
                rls.whereClause(), rls.params(), 1, 100);

        // PR #2 tie-breaker discipline: aggregate-only sort must end
        // in [groupColumn] ASC so paging stays deterministic.
        assertThat(q.sql())
                .contains("WHERE 1=1 AND [owner_id] IN (:_rlsIds)")
                .contains("ORDER BY [amount] DESC, [category] ASC OFFSET");

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());

        // Owner 100 → FIN (sum 30), Owner 200 → HR (sum 70). OPS hidden.
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("category"))
                .containsExactly("HR", "FIN"); // HR sum 70 > FIN sum 30 → DESC
    }
}
