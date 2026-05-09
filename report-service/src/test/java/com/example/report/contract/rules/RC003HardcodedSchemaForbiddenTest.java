package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Codex 019e0c99 iter-3 §D and iter-5 §3 absorb: RC-003 boundary regex
 * must catch single-digit tenant hardcodes in {@code sourceQuery} (the
 * {@code _7} bug that bypassed the previous 4-digit-year-only pattern)
 * while leaving the digit-less master {@code workcube_mikrolink} alone
 * and preserving existing static {@code sourceSchema} tier semantics.
 */
class RC003HardcodedSchemaForbiddenTest {

    private static final RC003HardcodedSchemaForbidden RULE_2026 =
            new RC003HardcodedSchemaForbidden(2026);

    private static ReportDefinition def(String key, String schemaMode,
                                        String sourceSchema, String sourceQuery,
                                        String yearColumn) {
        return new ReportDefinition(
                key, "v1", "Test", "desc", "cat",
                "TBL", sourceSchema, schemaMode, yearColumn, sourceQuery,
                List.of(new ColumnDefinition("id", "ID", "number", 100, false)),
                "id", "ASC",
                new AccessConfig(null, null, null, null));
    }

    // ─── sourceQuery — any numeric hardcode → FAIL ──────────────

    @Test
    void sourceQuery_singleDigitTenantHardcode_fails() {
        ReportDefinition d = def("fin-muhasebe-detay", "yearly", null,
                "SELECT * FROM [{schema}].[X] LEFT JOIN "
                        + "[workcube_mikrolink_7].[SETUP_PROCESS_CAT] SPC ON 1=1",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(v.get(0).message()).contains("workcube_mikrolink_7");
        assertThat(v.get(0).message()).contains("tenantId=7");
    }

    @Test
    void sourceQuery_multiDigitTenantHardcode_fails() {
        ReportDefinition d = def("fin-muhasebe-detay", "yearly", null,
                "SELECT * FROM [{schema}].[X] LEFT JOIN "
                        + "[workcube_mikrolink_35].[SETUP_PROCESS_CAT] SPC ON 1=1",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(v.get(0).message()).contains("workcube_mikrolink_35");
    }

    @Test
    void sourceQuery_yearTenantHardcode_fails() {
        ReportDefinition d = def("fin-muhasebe-detay", "yearly", null,
                "SELECT * FROM [workcube_mikrolink_2026_1].[ACCOUNT_CARD_ROWS]",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(v.get(0).message()).contains("year=2026");
        assertThat(v.get(0).message()).contains("tenantId=1");
    }

    @Test
    void sourceQuery_masterSchemaDigitless_passes() {
        ReportDefinition d = def("fin-cari-mutabakat", "yearly", null,
                "SELECT * FROM [{schema}].[X] LEFT JOIN "
                        + "[workcube_mikrolink].[COMPANY] C ON 1=1",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).isEmpty();
    }

    @Test
    void sourceQuery_correctPlaceholder_passes() {
        ReportDefinition d = def("fin-muhasebe-detay", "yearly", null,
                "SELECT * FROM [{schema}].[ACR] LEFT JOIN "
                        + "{tenantSetupProcessCatRelation} ON 1=1",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).isEmpty();
    }

    @Test
    void sourceQuery_multipleHardcodes_emitMultipleViolations() {
        ReportDefinition d = def("fin-multi", "yearly", null,
                "SELECT * FROM [workcube_mikrolink_7].[A] "
                        + "LEFT JOIN [workcube_mikrolink_2026_35].[B] ON 1=1",
                "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(2);
        assertThat(v).allMatch(x -> x.severity() == ContractViolation.Severity.FAIL);
    }

    // ─── sourceSchema — static-mode tier semantics preserved ────

    @Test
    void sourceSchema_staticCurrentYearTenant_fails() {
        ReportDefinition d = def("static-rep", "static",
                "workcube_mikrolink_2026_1", null, null);
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(v.get(0).field()).isEqualTo("sourceSchema");
    }

    @Test
    void sourceSchema_staticLegacyYearTenant_warns() {
        ReportDefinition d = def("static-rep", "static",
                "workcube_mikrolink_2024_1", null, null);
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(ContractViolation.Severity.WARN);
        assertThat(v.get(0).field()).isEqualTo("sourceSchema");
    }

    @Test
    void sourceSchema_staticSingleTenant_skipped_iter5_absorb() {
        // satis-ozet/stok-durum existing reports use this shape.
        // Yearly cleanup is a separate registry-migration PR; this PR
        // intentionally does NOT fail single-tenant sourceSchema.
        ReportDefinition d = def("satis-ozet", "static",
                "workcube_mikrolink_1", null, null);
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).isEmpty();
    }

    @Test
    void sourceSchema_yearlyMode_notScanned() {
        ReportDefinition d = def("yearly-rep", "yearly",
                "workcube_mikrolink_2026_1", null, "ACTION_DATE");
        List<ContractViolation> v = RULE_2026.validate(d);
        assertThat(v).isEmpty();
    }
}
