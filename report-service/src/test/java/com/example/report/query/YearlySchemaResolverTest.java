package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase 2 Program 2a — YearlySchemaResolver tenant guard hardening tests.
 *
 * <p>Codex iter-10 §2a-AGREE absorb (thread 019e0119): focused unit tests
 * (no Testcontainers — resolver contract not T-SQL correctness):
 * <ul>
 *   <li>yearly + no COMPANY scope → TenantSelectionRequiredException (400)</li>
 *   <li>yearly + COMPANY scope + schema exists → resolved schemas</li>
 *   <li>yearly + COMPANY scope + schema missing → SchemaResolverMissException (503)</li>
 *   <li>non-yearly (static) → unchanged def.sourceSchema() path</li>
 * </ul>
 */
class YearlySchemaResolverTest {

    private NamedParameterJdbcTemplate jdbc;
    private YearlySchemaResolver resolver;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbc.getJdbcTemplate()).thenReturn(jdbcTemplate);
        // Default: no schemas in sys.schemas (each test overrides).
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of());
        // Codex 019e0c99 iter-5 absorb: TenantMasterSchemaResolver dependency
        // injected (Phase 1 refactor). Default mock returns lookup available
        // for all tenants; specific tests override per scenario.
        TenantMasterSchemaResolver tenantMaster = mock(TenantMasterSchemaResolver.class);
        when(tenantMaster.resolveTenantSchema(anyLong()))
                .thenAnswer(inv -> "workcube_mikrolink_" + inv.<Long>getArgument(0));
        when(tenantMaster.isTenantLookupAvailable(anyLong(), anyString())).thenReturn(true);
        resolver = new YearlySchemaResolver(jdbc, tenantMaster);
    }

    @Test
    void resolve_nonYearlyReport_returnsSourceSchemaUnchanged() {
        ReportDefinition def = newDef("static", null, "workcube_mikrolink");
        AuthzMeResponse authz = newAuthz(Set.of("1"));

        YearlySchemaResolver.ResolvedSchemas result = resolver.resolve(def, authz, Map.of());

        assertThat(result.schemas()).containsExactly("workcube_mikrolink");
    }

    @Test
    void resolve_yearlyNoCompanyScope_throwsTenantSelectionRequired() {
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_1");
        AuthzMeResponse authz = newAuthz(Set.of());  // no COMPANY scope

        assertThatThrownBy(() -> resolver.resolve(def, authz, Map.of()))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("requires an explicit COMPANY scope")
                .hasMessageContaining("test-rep");
    }

    @Test
    void resolve_yearlyNullAuthz_throwsTenantSelectionRequired() {
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_1");

        assertThatThrownBy(() -> resolver.resolve(def, null, Map.of()))
                .isInstanceOf(TenantSelectionRequiredException.class);
    }

    @Test
    void resolve_yearlyWithCompanyScopeAndExistingSchema_returnsResolvedSchemas() {
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_1");
        AuthzMeResponse authz = newAuthz(Set.of("1"));

        // Stub sys.schemas to include current year + last year for company 1
        int currentYear = java.time.Year.now().getValue();
        when(jdbc.getJdbcTemplate().queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(
                        "workcube_mikrolink_" + currentYear + "_1",
                        "workcube_mikrolink_" + (currentYear - 1) + "_1"));

        YearlySchemaResolver.ResolvedSchemas result = resolver.resolve(def, authz, Map.of());

        assertThat(result.schemas())
                .as("Default no-filter path uses current year")
                .containsExactly("workcube_mikrolink_" + currentYear + "_1");
    }

    @Test
    void resolve_yearlyCompanyScopeButNoSchemaExists_throwsResolverMiss() {
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_99");
        AuthzMeResponse authz = newAuthz(Set.of("99"));
        // No schemas matching workcube_mikrolink_<year>_99 in sys.schemas

        assertThatThrownBy(() -> resolver.resolve(def, authz, Map.of()))
                .isInstanceOf(SchemaResolverMissException.class)
                .hasMessageContaining("Yearly schema resolver miss")
                .hasMessageContaining("test-rep")
                .extracting(e -> ((SchemaResolverMissException) e).attemptedSchemas())
                .satisfies(att -> {
                    @SuppressWarnings("unchecked")
                    List<String> attempted = (List<String>) att;
                    assertThat(attempted).isNotEmpty();
                    assertThat(attempted.get(0)).contains("workcube_mikrolink_");
                    assertThat(attempted.get(0)).contains("_99");
                });
    }

    @Test
    void resolve_superAdminWithExplicitPickerScope_resolvesSchemas() {
        // Super-admin path: CompanyHeaderScopeNarrower already populated COMPANY scope.
        // The resolver shouldn't care whether it's super-admin or regular —
        // explicit COMPANY scope is sufficient.
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_5");
        AuthzMeResponse authz = newAuthz(Set.of("5"));

        int currentYear = java.time.Year.now().getValue();
        when(jdbc.getJdbcTemplate().queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("workcube_mikrolink_" + currentYear + "_5"));

        YearlySchemaResolver.ResolvedSchemas result = resolver.resolve(def, authz, Map.of());

        assertThat(result.schemas()).containsExactly("workcube_mikrolink_" + currentYear + "_5");
    }

    @Test
    void resolve_superAdminWithoutExplicitPickerScope_throwsTenantSelectionRequired() {
        // Codex iter-11 §2a-AGREE absorb: super-admin no-picker path now
        // hits the same fail-closed contract. Legacy unrestricted bypass
        // for yearly execution is gone — picker header (X-Company-Id) is
        // mandatory for super-admin yearly reports too.
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_1");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(authz.getScopeRefIds("COMPANY")).thenReturn(Set.of());
        when(authz.isSuperAdmin()).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve(def, authz, Map.of()))
                .isInstanceOf(TenantSelectionRequiredException.class);
    }

    @Test
    void resolve_yearlyWithDateRangeFilter_resolvesAllYearsInRange() {
        ReportDefinition def = newDef("yearly", "ACTION_DATE", "workcube_mikrolink_1");
        AuthzMeResponse authz = newAuthz(Set.of("1"));

        when(jdbc.getJdbcTemplate().queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(
                        "workcube_mikrolink_2024_1",
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_1"));

        Map<String, Object> filter = Map.of(
                "ACTION_DATE", Map.of(
                        "filterType", "date",
                        "type", "inRange",
                        "filter", "2024-01-01",
                        "filterTo", "2026-12-31"));

        YearlySchemaResolver.ResolvedSchemas result = resolver.resolve(def, authz, filter);

        assertThat(result.schemas())
                .containsExactlyInAnyOrder(
                        "workcube_mikrolink_2024_1",
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_1");
    }

    private static ReportDefinition newDef(String schemaMode, String yearColumn, String sourceSchema) {
        return new ReportDefinition(
                "test-rep", "1.0", "Test Report", null, "Finans",
                "TEST_TABLE", sourceSchema, schemaMode, yearColumn, null,
                List.of(new ColumnDefinition("X", "X", "text", 100, false)),
                "X", "ASC",
                new AccessConfig("perm", null, null, null));
    }

    private static AuthzMeResponse newAuthz(Set<String> companyIds) {
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(authz.getScopeRefIds("COMPANY")).thenReturn(companyIds);
        when(authz.isSuperAdmin()).thenReturn(false);
        return authz;
    }
}
