package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.ReportingAllowlist;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 11.1 — RC-011 unit tests (Adım 11.1).
 *
 * <p>Codex iter-17 PARTIAL absorb: allowlist membership is a separate
 * contract rule (not a policy enum extension). Tests cover source-field
 * membership and the absence-of-source bypass; sourceQuery scanning is
 * planned for Adım 11.2 (WorkcubeQueryAdapter SQL parsing).
 */
class RC011WorkcubeSourceAllowlistedTest {

    private final RC011WorkcubeSourceAllowlisted rule = new RC011WorkcubeSourceAllowlisted();

    private ReportDefinition def(String key, String source) {
        return new ReportDefinition(
                key, "1.0", "Title", "Description", "category",
                source, "dbo", "static", null, null,
                List.of(new ColumnDefinition("col", "col", "STRING", null, false, false, false, null)),
                null, "ASC", null
        );
    }

    private ReportDefinition defSourceQuery(String key, String sourceQuery) {
        return new ReportDefinition(
                key, "1.0", "Title", "Description", "category",
                null, null, "yearly", "year", sourceQuery,
                List.of(new ColumnDefinition("col", "col", "STRING", null, false, false, false, null)),
                null, "ASC", null
        );
    }

    @Test
    void ruleId_is_RC011() {
        assertThat(rule.ruleId()).isEqualTo("RC-011");
    }

    @Test
    void canonical_source_in_V1_passes() {
        List<ContractViolation> v = rule.validate(def("inv-report", "INVOICE"));
        assertThat(v).isEmpty();
    }

    @Test
    void canonical_source_case_insensitive_passes() {
        List<ContractViolation> v = rule.validate(def("inv-report", "invoice"));
        assertThat(v).isEmpty();
    }

    @Test
    void unknown_source_fails_with_helpful_message() {
        List<ContractViolation> v = rule.validate(def("rogue-report", "UNKNOWN_RANDOM_TABLE"));
        assertThat(v).hasSize(1);
        ContractViolation viol = v.get(0);
        assertThat(viol.ruleId()).isEqualTo("RC-011");
        assertThat(viol.reportKey()).isEqualTo("rogue-report");
        assertThat(viol.field()).isEqualTo("source");
        assertThat(viol.message()).contains("UNKNOWN_RANDOM_TABLE");
        assertThat(viol.message()).contains("ReportingAllowlist.V1");
    }

    @Test
    void null_source_bypasses_rule() {
        // sourceQuery-only report (yearly schemaMode) — RC-011 skips, Adım 11.2 covers
        List<ContractViolation> v = rule.validate(defSourceQuery("yearly-rpt",
                "SELECT * FROM workcube_mikrolink_{year}_{tenantId}.CARI_ROWS"));
        assertThat(v).isEmpty();
    }

    @Test
    void blank_source_bypasses_rule() {
        // ReportDefinition validates source/sourceQuery in its compact ctor; can't pass blank
        // directly. Synthesized: source field handling — we test the helper directly.
        assertThat(ReportingAllowlist.containsV1("")).isFalse();
        assertThat(ReportingAllowlist.containsV1(null)).isFalse();
        assertThat(ReportingAllowlist.containsV1(" ")).isFalse();
    }

    @Test
    void allowlist_v1_size_documents_pre_seal_baseline() {
        // Doc-style assertion: ReportingAllowlist.V1 size is the V1 pre-SEAL count.
        // Codex iter-17 S2: SEAL sonrası V2 ile değişecek; bu test V1 baseline'ı
        // kayıt altına alır (drift'i regression olarak yakalar).
        assertThat(ReportingAllowlist.V1).hasSize(40);
    }

    @Test
    void allowlist_v1_contains_ADR_0012_SS_canonical() {
        // ADR-0012-SS §2.2 listed canonical 23 tables — sanity-spot check
        assertThat(ReportingAllowlist.V1).contains(
                "INVOICE", "INVOICE_ROW", "CARI_ROWS", "CARI_ACTIONS",
                "BANK_ACTIONS", "CASH_ACTIONS", "CHEQUE", "COMPANY_REMAINDER",
                "ORDERS", "ORDER_ROW", "OUR_COMPANY", "BRANCH",
                "DEPARTMENT", "PRO_PROJECTS"
        );
    }
}
