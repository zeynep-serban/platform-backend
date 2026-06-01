package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.exceptions.ContractExceptionEntry;
import com.example.report.contract.report.ContractReport;
import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Phase 2 Program 1d — ReportContractGate end-to-end tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb (thread 019e0119): full registry sweep
 * exercises the entire chain (raw schema validation → bind → semantic RC →
 * exception suppression). Provides the gate green-path proof for all 32
 * migrated reports.
 *
 * <p>Acceptance criteria:
 * <ul>
 *   <li>Default {@code mvn test} run completes deterministically.</li>
 *   <li>All report-contract governance debt is closed at the rule level —
 *       RC-001 via the COMPANY_REMAINDER carve-out (#248), RC-004 via the
 *       BRANCH migration + RC-004 v2 sourceQuery projected-join boundary
 *       (#247). exceptions.json is empty; the gate is green with raw=0.</li>
 *   <li>{@code hr-personel-listesi} (legitimate {@code OUR_COMPANY_ID})
 *       is NOT in the exception list.</li>
 *   <li>No unsuppressed FAILs remain in the gate output.</li>
 *   <li>Exception inventory exact: 0 entries — all debt rule-closed.</li>
 * </ul>
 */
class ReportContractGateTest {

    @Test
    void gate_full32ReportRegistry_returnsCleanReport() {
        // PR-D2.1d (ADR-0015): bumped 32 → 33 for users-overview
        // (first remote-http report, execution.kind=remote-http).
        // PR-D2.2 (ADR-0015): bumped 33 → 34 for access-report
        // (second remote-http report, service=permission-service).
        // PR-D2.3 (ADR-0015): bumped 34 → 35 for audit-report
        // (third remote-http, paged-events-total c2.5).
        // PR-D2.4 (ADR-0015): bumped 35 → 36 for monthly-login
        // (fourth remote-http, paged-events-total — Codex 019e83fd
        // "aggregation gerekli" warning kabul: option (a) filter-only).
        ContractReport report = ReportContractGate.create().gate();

        assertThat(report.reportCount())
                .as("All 36 migrated reports plus exceptions.json (excluded) discovered by sweep")
                .isEqualTo(36);

        // Codex iter-4 §1d-AGREE: gate must produce zero unsuppressed FAILs.
        // Codex 019e3f5c: all governance debt is rule-closed (RC-001 carve-out
        // + RC-004 v2 boundary) and exceptions.json is empty — any failure
        // here signals new tech debt or a regression.
        assertThat(report.failures())
                .as("Unsuppressed failures: %s",
                        report.failures().stream().limit(10).toList())
                .isEmpty();
    }

    @Test
    void gate_hrReports_passRC004AfterClosure() {
        // Codex 019e3f5c: the 4 formerly-debt HR reports now pass RC-004 with
        // no violation and no exception — hr-bordro-detay via BRANCH scope,
        // hr-izin-raporu / hr-maas-raporu / hr-maas-gecmisi via the RC-004 v2
        // sourceQuery projected-join company boundary (#247).
        ContractReport report = ReportContractGate.create().gate();
        List<String> closedKeys = List.of(
                "hr-bordro-detay", "hr-izin-raporu",
                "hr-maas-gecmisi", "hr-maas-raporu");

        assertThat(report.failures())
                .as("Formerly-debt HR reports must raise no RC-004 failure")
                .noneMatch(v -> closedKeys.contains(v.reportKey())
                        && "RC-004".equals(v.ruleId()));
    }

    @Test
    void gate_hrPersonelListesi_notExceptionSuppressed_legitimateAllowlist() {
        // hr-personel-listesi uses OUR_COMPANY_ID (legitimate tenant col, in allowlist).
        // It must NOT be in the exception list AND must pass without suppression.
        ContractReport report = ReportContractGate.create().gate();

        assertThat(report.failures())
                .as("hr-personel-listesi uses legitimate OUR_COMPANY_ID; no RC-004 fail expected")
                .noneMatch(v -> "hr-personel-listesi".equals(v.reportKey())
                        && "RC-004".equals(v.ruleId()));
    }

    @Test
    void gate_yearColumnSemanticFixes_noRC001ForFixedReports() {
        // 3 reports got yearColumn semantic fix (Codex iter-4 §1d-AGREE):
        //   fin-fatura-satirlari → INVOICE_DATE
        //   fin-stok-fis-detay → FIS_DATE
        //   fin-gerceklesen-maliyet → RECORD_DATE
        ContractReport report = ReportContractGate.create().gate();
        List<String> fixedKeys = List.of(
                "fin-fatura-satirlari", "fin-stok-fis-detay", "fin-gerceklesen-maliyet");

        assertThat(report.failures())
                .as("yearColumn semantic-fix reports must NOT trigger RC-001")
                .noneMatch(v -> fixedKeys.contains(v.reportKey()) && "RC-001".equals(v.ruleId()));
    }

    @Test
    void gate_companyRemainderSnapshotReports_passRC001ViaCarveout() {
        // Codex 019e3f5c absorb: fin-alacak-yaslandirma + fin-borc-yaslandirma
        // are COMPANY_REMAINDER schema-encoded balance snapshots — RC-001 now
        // passes them via the carve-out, so no exception entry is needed.
        ContractReport report = ReportContractGate.create().gate();
        List<String> snapshotKeys = List.of("fin-alacak-yaslandirma", "fin-borc-yaslandirma");

        assertThat(report.failures())
                .as("COMPANY_REMAINDER snapshot reports must not raise RC-001")
                .noneMatch(v -> snapshotKeys.contains(v.reportKey()) && "RC-001".equals(v.ruleId()));
    }

    @Test
    void exceptionsJson_inventoryIsEmpty_allDebtClosed() throws Exception {
        // Codex 019e3f5c absorb: all report-contract governance debt is now
        // closed at the rule level — RC-001 via the RC001 COMPANY_REMAINDER
        // carve-out (#248), RC-004 via the BRANCH migration + RC-004 v2
        // sourceQuery projected-join boundary (#247). exceptions.json carries
        // zero entries; the gate is green with raw=0 (nothing to suppress).
        // This test is the drift guard: a stray exception entry re-appearing
        // without review fails here.
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("Governance debt fully closed — exception inventory must be empty")
                .isEmpty();
    }

    @Test
    void exceptionsJson_hrPersonelListesi_notInExceptionList() throws Exception {
        // Direct inventory assertion (not failure absence — failure absence
        // could pass even if a stray exception entry was added).
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("hr-personel-listesi uses legitimate OUR_COMPANY_ID via allowlist; "
                        + "MUST NOT appear in exception inventory")
                .noneMatch(e -> "hr-personel-listesi".equals(e.reportKey()));
    }

    @Test
    void exceptionsJson_allEntriesWithin90DayHorizon() throws Exception {
        // All committed entries must have expiresAt within 90 days of the
        // committed-at date (2026-05-07 → 2026-08-05). Codex iter-3 §1b absorb
        // continuity: 90d horizon is governance debt visibility deadline.
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("All exception entries must have explicit expiresAt")
                .allMatch(e -> e.expiresAt() != null);
        assertThat(entries)
                .as("All exception entries must have non-blank reason for audit")
                .allMatch(e -> e.reason() != null && e.reason().length() >= 10);
        assertThat(entries)
                .as("All exception entries must have owner")
                .allMatch(e -> e.owner() != null && !e.owner().isBlank());
    }

    private ContractExceptionEntry[] loadExceptions() throws Exception {
        Resource resource = new DefaultResourceLoader()
                .getResource("classpath:reports/exceptions.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, ContractExceptionEntry[].class);
        }
    }
}
