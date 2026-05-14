package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.report.contract.schema.TableRef;
import com.example.report.contract.schema.TableRef.SchemaKind;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 11.2c — composite tenant boundary unit tests (Codex
 * iter-27 PARTIAL absorb).
 *
 * <p>Critical invariant: same tenantId across all tenant-scoped refs;
 * year axis may differ; canonical refs bypass.
 */
class CompositeTenantBoundaryEnforcerTest {

    private final CompositeTenantBoundaryEnforcer enforcer = new CompositeTenantBoundaryEnforcer();

    private ReportDefinition def(String key) {
        return new ReportDefinition(
                key, "1.0", "Title", "Description", "category",
                "INVOICE", "dbo", "static", null, null,
                List.of(new ColumnDefinition("col", "col", "STRING", null, false, false, false, null)),
                null, "ASC", null
        );
    }

    private TableRef ref(SchemaKind kind, String schema, String table) {
        return new TableRef(
                "[" + schema + "].[" + table + "]",
                schema, table, kind, "FROM", 0);
    }

    // ---- Pass cases -------------------------------------------------------

    @Test
    void validateComposite_allCanonical_passes() {
        // All canonical (master) refs — tenant-agnostic.
        List<TableRef> refs = List.of(
                ref(SchemaKind.CANONICAL, "workcube_mikrolink", "COMPANY"),
                ref(SchemaKind.CANONICAL, "workcube_mikrolink", "BRANCH"));
        enforcer.validateComposite(refs, def("canonical-only"));
    }

    @Test
    void validateComposite_singleYearlyPartition_passes() {
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"));
        enforcer.validateComposite(refs, def("single-year"));
    }

    @Test
    void validateComposite_multiYearSameTenant_passes() {
        // Codex iter-27 critical: same tenant across multiple years OK.
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2025_35", "INVOICE"),
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"));
        enforcer.validateComposite(refs, def("multi-year-same-tenant"));
    }

    @Test
    void validateComposite_yearlyPlusCanonical_passes() {
        // Most common composite: yearly tenant + canonical lookup.
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"),
                ref(SchemaKind.CANONICAL, "workcube_mikrolink", "COMPANY"));
        enforcer.validateComposite(refs, def("yearly-plus-canonical"));
    }

    @Test
    void validateComposite_yearlyPlusCurrentTenantSameTenant_passes() {
        // Codex iter-27 absorb: {tenantSetupProcessCatRelation} expansion
        // produces CURRENT_TENANT refs; must share tenantId with yearly.
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"),
                ref(SchemaKind.CURRENT_TENANT, "workcube_mikrolink_35", "SETUP_PROCESS_CAT"));
        enforcer.validateComposite(refs, def("yearly-plus-current-same-tenant"));
    }

    @Test
    void validateComposite_emptyRefs_passes() {
        // No tenant-scoped refs (caller earlier checks for non-empty refs)
        enforcer.validateComposite(List.of(), def("empty"));
    }

    // ---- Fail cases -------------------------------------------------------

    @Test
    void validateComposite_multipleTenantIds_fails() {
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"),
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_99", "INVOICE_ROW"));
        assertThatThrownBy(() -> enforcer.validateComposite(refs, def("cross-tenant")))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("multiple tenant ids")
                .hasMessageContaining("cross-tenant");
    }

    @Test
    void validateComposite_yearlyAndCurrentTenantDifferentTenant_fails() {
        // Tenant 35 yearly + tenant 99 current → cross-tenant violation
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE"),
                ref(SchemaKind.CURRENT_TENANT, "workcube_mikrolink_99", "SETUP_PROCESS_CAT"));
        assertThatThrownBy(() -> enforcer.validateComposite(refs, def("cross-yearly-current")))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("multiple tenant ids");
    }

    @Test
    void validateComposite_unparseableTenantSchemaKind_failsClosed() {
        // Scanner shouldn't produce this (it would have classified as UNKNOWN),
        // but defense-in-depth: enforcer fails-closed on parse failure.
        List<TableRef> refs = List.of(
                ref(SchemaKind.YEARLY_PARTITION, "workcube_garbage_schema", "INVOICE"));
        assertThatThrownBy(() -> enforcer.validateComposite(refs, def("unparseable")))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("could not be parsed");
    }

    // ---- extractTenantId helper -------------------------------------------

    @Test
    void extractTenantId_yearlyPartition_extractsTenant() {
        TableRef r = ref(SchemaKind.YEARLY_PARTITION, "workcube_mikrolink_2026_35", "INVOICE");
        assertThat(enforcer.extractTenantId(r)).contains(35L);
    }

    @Test
    void extractTenantId_currentTenant_extractsTenant() {
        TableRef r = ref(SchemaKind.CURRENT_TENANT, "workcube_mikrolink_35", "SETUP_PROCESS_CAT");
        assertThat(enforcer.extractTenantId(r)).contains(35L);
    }

    @Test
    void extractTenantId_canonical_returnsEmpty() {
        TableRef r = ref(SchemaKind.CANONICAL, "workcube_mikrolink", "COMPANY");
        assertThat(enforcer.extractTenantId(r)).isEmpty();
    }
}
