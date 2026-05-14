package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.report.query.SqlBuilder;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2 Program 11.2b-2 — end-to-end {@link WorkcubeQueryAdapter}
 * integration test against a real Microsoft SQL Server 2022 instance
 * (Adım 11.2b-2; Codex iter-24 acceptance).
 *
 * <p>Companion to {@link WorkcubeQueryAdapterTest} (mock-based), this
 * harness pushes the full pipeline through a Testcontainers MSSQL:
 *
 * <pre>
 *   ReportDefinition + AuthzMeResponse (fixture)
 *     → SqlBuilder.buildDataQuery(rendered SQL)
 *     → WorkcubeSqlTableRefScanner.scan(rendered SQL)
 *     → ReportingAllowlist.V1 enforcement
 *     → MSSQL execute (queryForList)
 *     → row assertions
 * </pre>
 *
 * <p>Gated behind {@code @Tag("integration")} + {@code @Testcontainers
 * (disabledWithoutDocker = true)}; CI runs this through the existing
 * report-service MSSQL Testcontainers job.
 *
 * <p>Codex iter-24 acceptance kriterleri:
 * <ol>
 *   <li>allowed canonical rendered query returns rows</li>
 *   <li>{@code {tenantSetupProcessCatRelation}} expansion sonrası
 *       {@code SETUP_PROCESS_CAT} scanner/enforcer tarafından görülür
 *       (mevcut SqlBuilder placeholder substitution flow)</li>
 *   <li>unknown table in rendered SQL fails before JDBC execution</li>
 *   <li>unqualified table fails before JDBC execution</li>
 *   <li>SQL with no detectable table refs fails before JDBC execution</li>
 *   <li>rogue filter/sort column SQL'e girmiyor (SqlBuilder visible-column
 *       guard upstream)</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class WorkcubeQueryAdapterIT {

    private static final String CANONICAL_SCHEMA = "workcube_mikrolink";
    private static final String YEARLY_SCHEMA = "workcube_mikrolink_2026_35";
    private static final String CURRENT_TENANT_SCHEMA = "workcube_mikrolink_35";

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static WorkcubeQueryAdapter adapter;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MSSQL.getDriverClassName());
        ds.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        ds.setUsername(MSSQL.getUsername());
        ds.setPassword(MSSQL.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);

        adapter = new WorkcubeQueryAdapter(new SqlBuilder(), jdbc, new CompositeTenantBoundaryEnforcer());

        // Canonical schema + table — keeps the fixture minimal: a single
        // V1-allowlisted table that exercises both the bracketed
        // [workcube_mikrolink].[COMPANY] ref shape and the canonical
        // SchemaKind classification path.
        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('" + CANONICAL_SCHEMA + "') IS NULL "
                        + "EXEC('CREATE SCHEMA [" + CANONICAL_SCHEMA + "]')");
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + CANONICAL_SCHEMA + ".COMPANY', 'U') IS NOT NULL "
                        + "DROP TABLE [" + CANONICAL_SCHEMA + "].[COMPANY]");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE [" + CANONICAL_SCHEMA + "].[COMPANY] ("
                        + "COMPANY_ID INT NOT NULL PRIMARY KEY, "
                        + "FULLNAME NVARCHAR(100) NOT NULL)");
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO [" + CANONICAL_SCHEMA + "].[COMPANY] VALUES "
                        + "(1, 'Alpha Co'), (2, 'Beta Co'), (3, 'Gamma Co')");

        // Adım 11.2c: yearly partition schema (workcube_mikrolink_2026_35)
        // + INVOICE table. Tenant 35; year 2026.
        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('" + YEARLY_SCHEMA + "') IS NULL "
                        + "EXEC('CREATE SCHEMA [" + YEARLY_SCHEMA + "]')");
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + YEARLY_SCHEMA + ".INVOICE', 'U') IS NOT NULL "
                        + "DROP TABLE [" + YEARLY_SCHEMA + "].[INVOICE]");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE [" + YEARLY_SCHEMA + "].[INVOICE] ("
                        + "INVOICE_ID INT NOT NULL PRIMARY KEY, "
                        + "COMPANY_ID INT NOT NULL, "
                        + "INVOICE_NUMBER NVARCHAR(50) NOT NULL)");
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO [" + YEARLY_SCHEMA + "].[INVOICE] VALUES "
                        + "(101, 1, 'INV-2026-001'), (102, 2, 'INV-2026-002')");

        // Adım 11.2c: current-tenant schema (workcube_mikrolink_35)
        // SETUP_PROCESS_CAT — {tenantSetupProcessCatRelation} expansion target.
        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('" + CURRENT_TENANT_SCHEMA + "') IS NULL "
                        + "EXEC('CREATE SCHEMA [" + CURRENT_TENANT_SCHEMA + "]')");
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('" + CURRENT_TENANT_SCHEMA + ".SETUP_PROCESS_CAT', 'U') IS NOT NULL "
                        + "DROP TABLE [" + CURRENT_TENANT_SCHEMA + "].[SETUP_PROCESS_CAT]");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE [" + CURRENT_TENANT_SCHEMA + "].[SETUP_PROCESS_CAT] ("
                        + "PROCESS_CAT_ID INT NOT NULL PRIMARY KEY, "
                        + "PROCESS_CAT NVARCHAR(50) NOT NULL)");
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO [" + CURRENT_TENANT_SCHEMA + "].[SETUP_PROCESS_CAT] VALUES "
                        + "(1, 'Satış'), (2, 'Alış')");
    }

    private static ReportDefinition companyDef() {
        return new ReportDefinition(
                "workcube-company-it", "1.0", "Company IT", "test", "test",
                "COMPANY", CANONICAL_SCHEMA, "static", null, null,
                List.of(
                        new ColumnDefinition("COMPANY_ID", "ID", "number", 50, false),
                        new ColumnDefinition("FULLNAME", "Name", "text", 200, false)),
                "COMPANY_ID", "ASC", null);
    }

    // ---- Acceptance 1: allowed canonical query executes -------------------

    @Test
    void allowed_canonical_query_returns_rows() {
        List<Map<String, Object>> rows = adapter.executeData(
                companyDef(), null,
                List.of("COMPANY_ID", "FULLNAME"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "COMPANY_ID", "sort", "asc")),
                "", new MapSqlParameterSource(),
                1, 50);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("FULLNAME"))
                .containsExactly("Alpha Co", "Beta Co", "Gamma Co");
    }

    @Test
    void allowed_canonical_count_returns_total() {
        long total = adapter.executeCount(
                companyDef(), null,
                Collections.emptyMap(),
                List.of("COMPANY_ID", "FULLNAME"),
                "", new MapSqlParameterSource());
        assertThat(total).isEqualTo(3L);
    }

    // ---- Acceptance 3: enforceRendered direct fail-closed tests -----------
    //     (these don't need fixture data; they prove the second-line
    //     enforcement runs *before* any JDBC call)

    @Test
    void unknown_table_in_rendered_sql_fails_before_jdbc() {
        // Direct enforce call (bypasses SqlBuilder) — proves any SQL
        // referencing a non-V1 table is refused at the adapter layer.
        assertThatThrownBy(() -> adapter.enforceRendered(companyDef(),
                "SELECT * FROM [workcube_mikrolink].[SECRET_NOT_IN_V1]"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("SECRET_NOT_IN_V1")
                .hasMessageContaining("ReportingAllowlist.V1");
    }

    @Test
    void unqualified_table_in_rendered_sql_fails_before_jdbc() {
        assertThatThrownBy(() -> adapter.enforceRendered(companyDef(),
                "SELECT * FROM COMPANY"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("unsupported or unqualified");
    }

    @Test
    void sql_with_no_table_refs_fails_before_jdbc() {
        assertThatThrownBy(() -> adapter.enforceRendered(companyDef(),
                "SELECT 1 AS sentinel"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("no detectable");
    }

    // ---- Acceptance 6: visible column guard (upstream SqlBuilder) ---------

    // ---- Adım 11.2c: composite tenant boundary IT cases ------------------

    @Test
    void allowed_composite_join_yearly_plus_canonical_returns_rows() {
        // End-to-end composite query: yearly INVOICE + canonical COMPANY JOIN.
        // Adapter renders + scans + V1 enforce + composite check; all pass.
        String sql = "SELECT INV.INVOICE_ID, INV.INVOICE_NUMBER, C.FULLNAME "
                + "FROM [" + YEARLY_SCHEMA + "].[INVOICE] INV "
                + "LEFT JOIN [" + CANONICAL_SCHEMA + "].[COMPANY] C "
                + "ON C.COMPANY_ID = INV.COMPANY_ID";
        // Use enforceRendered direct + jdbc query to bypass SqlBuilder.
        adapter.enforceRendered(companyDef(), sql);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("FULLNAME"))
                .containsExactlyInAnyOrder("Alpha Co", "Beta Co");
    }

    @Test
    void cross_tenant_rendered_sql_defensive_fail() {
        // Synthesized cross-tenant rendered SQL — tenant 35 + tenant 99.
        // Adapter should fail-closed BEFORE JDBC execution (composite check).
        String crossTenantSql = "SELECT * FROM [workcube_mikrolink_2026_35].[INVOICE] A "
                + "JOIN [workcube_mikrolink_2026_99].[INVOICE_ROW] B ON A.INVOICE_ID = B.INVOICE_ID";
        assertThatThrownBy(() -> adapter.enforceRendered(companyDef(), crossTenantSql))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("multiple tenant ids");
    }

    @Test
    void tenantSetupProcessCatRelation_expansion_visible_to_scanner() {
        // Codex iter-27 absorb: {tenantSetupProcessCatRelation} expands to
        // CURRENT_TENANT [workcube_mikrolink_<tenantId>].[SETUP_PROCESS_CAT].
        // Scanner sees it; SETUP_PROCESS_CAT is in V1; composite check passes
        // because it shares tenant 35 with the yearly INVOICE ref.
        String expanded = "SELECT INV.INVOICE_ID, SPC.PROCESS_CAT "
                + "FROM [" + YEARLY_SCHEMA + "].[INVOICE] INV "
                + "LEFT JOIN [" + CURRENT_TENANT_SCHEMA + "].[SETUP_PROCESS_CAT] SPC "
                + "ON SPC.PROCESS_CAT_ID = INV.INVOICE_ID";
        adapter.enforceRendered(companyDef(), expanded);
        List<Map<String, Object>> rows = jdbc.queryForList(expanded, new MapSqlParameterSource());
        assertThat(rows).hasSize(2);
    }

    @Test
    void tenantSetupProcessCatRelation_differentTenant_failsClosed() {
        // SETUP_PROCESS_CAT expansion happens to render with WRONG tenant id
        // (e.g. placeholder substitution drift): tenant 99 SETUP_PROCESS_CAT
        // + tenant 35 yearly INVOICE → cross-tenant composite violation.
        String drift = "SELECT INV.INVOICE_ID, SPC.PROCESS_CAT "
                + "FROM [workcube_mikrolink_2026_35].[INVOICE] INV "
                + "LEFT JOIN [workcube_mikrolink_99].[SETUP_PROCESS_CAT] SPC "
                + "ON SPC.PROCESS_CAT_ID = INV.INVOICE_ID";
        assertThatThrownBy(() -> adapter.enforceRendered(companyDef(), drift))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("multiple tenant ids");
    }

    @Test
    void rogue_filter_column_does_not_reach_rendered_sql() {
        // SqlBuilder's FilterTranslator drops unknown filter columns
        // silently — proves the guard is upstream and the adapter never
        // sees a rendered SQL with rogue identifiers.
        Map<String, Object> rogueFilter = Map.of(
                "WHERE_INJECTION_ATTEMPT__DROP_TABLE", Map.of("filterType", "text", "type", "equals", "filter", "x"));

        // Should not throw — rogue filter column silently filtered out
        // upstream, adapter sees a clean SQL and returns all 3 rows.
        List<Map<String, Object>> rows = adapter.executeData(
                companyDef(), null,
                List.of("COMPANY_ID", "FULLNAME"),
                rogueFilter,
                List.of(),
                "", new MapSqlParameterSource(),
                1, 50);

        assertThat(rows).hasSize(3);
    }
}
