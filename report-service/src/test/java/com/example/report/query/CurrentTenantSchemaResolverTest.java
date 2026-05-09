package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Codex 019e0d06 iter-2 absorb: current-tenant resolver behavior tests.
 * Coverage: single auto-pick, multi-company tenant_selection_required,
 * empty scope, schema existence miss (503), normal happy path.
 */
class CurrentTenantSchemaResolverTest {

    private TenantMasterSchemaResolver tenantMaster;
    private YearlySchemaResolver yearlyResolver;
    private CurrentTenantSchemaResolver resolver;

    @BeforeEach
    void setUp() {
        tenantMaster = mock(TenantMasterSchemaResolver.class);
        yearlyResolver = mock(YearlySchemaResolver.class);
        when(tenantMaster.resolveTenantSchema(anyLong()))
                .thenAnswer(inv -> "workcube_mikrolink_" + inv.<Long>getArgument(0));
        when(tenantMaster.isTenantLookupAvailable(anyLong(), anyString())).thenReturn(true);
        // Default: schema 1, 35 exist; 50 inactive.
        when(yearlyResolver.getAvailableSchemas())
                .thenReturn(Set.of("workcube_mikrolink_1", "workcube_mikrolink_35"));
        resolver = new CurrentTenantSchemaResolver(tenantMaster, yearlyResolver);
    }

    private static ReportDefinition currentDef() {
        return new ReportDefinition(
                "test-current", "v1", "Test current", "desc", "Satış",
                "ORDERS", null, "current", null, null,
                List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                "id", "ASC",
                new AccessConfig(null, null, null, null));
    }

    private static AuthzMeResponse newAuthz(Set<String> companyIds) {
        AuthzMeResponse a = mock(AuthzMeResponse.class);
        when(a.getScopeRefIds("COMPANY")).thenReturn(companyIds);
        return a;
    }

    @Test
    void singleCompanyScope_autoPick_resolvesTenantBranch() {
        var result = resolver.resolve(currentDef(), newAuthz(Set.of("35")));
        assertThat(result.branches()).hasSize(1);
        var branch = result.branches().get(0);
        assertThat(branch.tenantId()).isEqualTo(35L);
        assertThat(branch.transactionSchema()).isEqualTo("workcube_mikrolink_35");
        assertThat(branch.tenantSchema()).isEqualTo("workcube_mikrolink_35");
        assertThat(branch.tenantLookupAvailable()).isTrue();
    }

    @Test
    void emptyScope_throwsTenantSelectionRequired() {
        assertThatThrownBy(() -> resolver.resolve(currentDef(), newAuthz(Set.of())))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("requires an explicit COMPANY scope");
    }

    @Test
    void multiCompanyScope_throwsTenantSelectionRequired_iter2_absorb() {
        // Multi-company scope = X-Company-Id header eksik veya scope match yok.
        // CompanyHeaderScopeNarrower.narrow() öncesinde gelirse buradan
        // tenant_selection_required dönmeli (Codex iter-2 §5).
        assertThatThrownBy(() -> resolver.resolve(currentDef(), newAuthz(Set.of("1", "35"))))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("X-Company-Id picker for multi-company");
    }

    @Test
    void schemaMissing_throwsResolverMiss_failClosed_iter2_absorb() {
        // Codex iter-2 §6 — current schema yoksa 503 fail-closed; empty
        // rowset degrade YOK (sadece optional lookup için reserved).
        assertThatThrownBy(() -> resolver.resolve(currentDef(), newAuthz(Set.of("99"))))
                .isInstanceOf(SchemaResolverMissException.class)
                .hasMessageContaining("expected master schema 'workcube_mikrolink_99' not found");
    }

    @Test
    void nonNumericScope_throwsTenantSelectionRequired() {
        assertThatThrownBy(() -> resolver.resolve(currentDef(), newAuthz(Set.of("not-a-number"))))
                .isInstanceOf(TenantSelectionRequiredException.class)
                .hasMessageContaining("non-numeric");
    }
}
