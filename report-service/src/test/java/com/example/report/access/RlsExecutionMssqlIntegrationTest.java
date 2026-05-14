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
                        + "category NVARCHAR(20) NOT NULL, "
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
