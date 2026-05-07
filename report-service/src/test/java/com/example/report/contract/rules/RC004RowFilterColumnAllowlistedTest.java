package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 1d — RC-004 row filter column allowlist tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb: allowlist-only check (existence cross-check
 * deferred to Phase 2 Program 2). Two distinct fail modes covered:
 * <ul>
 *   <li>Allowlist miss → "Column not in tenant column allowlist for source"</li>
 *   <li>Source unresolved (sourceQuery + no source) → single fail, skip rest</li>
 * </ul>
 */
class RC004RowFilterColumnAllowlistedTest {

    private static final TenantColumnAllowlist ALLOWLIST = new TenantColumnAllowlist(Map.of(
            "INVOICE", List.of("COMPANY_ID"),
            "CARI_ROWS", List.of("FROM_CMP_ID", "OUR_COMPANY_ID")));

    @Test
    void validate_companyRowFilter_columnInAllowlist_returnsEmpty() {
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_companyRowFilter_columnNotInAllowlist_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("INVOICE", "DEPARTMENT_ID");  // not allowlisted

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist for source 'INVOICE'");
    }

    @Test
    void validate_companyRowFilter_unknownSourceTable_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("UNKNOWN_TABLE", "ANY_COL");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("not in tenant column allowlist for source 'UNKNOWN_TABLE'"));
    }

    @Test
    void validate_companyRowFilter_sourceQueryNoSource_singleFailFastReturn() {
        // sourceQuery + no source: single fail, skip allowlist check.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                null,  // source absent
                "workcube_mikrolink_1", "static", null,
                "SELECT * FROM {schema}.SOMETHING WHERE COMPANY_ID = ?",
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("COMPANY_ID", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).message())
                .contains("COMPANY rowFilter requires resolvable source table");
    }

    @Test
    void validate_nonCompanyScope_returnsEmpty_skipsAllowlistEntirely() {
        // BRANCH scopeType not subject to RC-004 allowlist.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "EXPENSE_ITEM_PLANS", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("BRANCH_ID", "B", "number", 100, false)),
                "BRANCH_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("BRANCH_ID", "BRANCH", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_noRowFilter_returnsEmpty() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                null);  // no access

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_blankColumn_failsWithRequiredColumnMessage() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("requires non-blank column"));
    }

    @Test
    void validate_emptyAllowlistDefault_failsAllCompanyRowFilters() {
        // Backward-compat: no-arg constructor uses empty allowlist; everything
        // fails. Production callers must inject real allowlist via overload.
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted().validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist");
    }

    private ReportDefinition newDef(String source, String rowFilterColumn) {
        return new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                source, "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition(rowFilterColumn, "C", "number", 100, false)),
                rowFilterColumn, "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter(rowFilterColumn, "COMPANY", null)));
    }
}
