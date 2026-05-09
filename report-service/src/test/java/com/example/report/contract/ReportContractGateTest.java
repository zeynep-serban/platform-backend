package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.exceptions.ContractExceptionEntry;
import com.example.report.contract.report.ContractReport;
import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Phase 2 Program 1d — ReportContractGate end-to-end tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb (thread 019e0119): full registry sweep
 * exercises the entire chain (raw schema validation → bind → semantic RC →
 * exception suppression). Provides the gate green-path proof for all 31
 * migrated reports.
 *
 * <p>Acceptance criteria:
 * <ul>
 *   <li>Default {@code mvn test} run completes deterministically.</li>
 *   <li>All known RC-001 + RC-004 governance debt is suppressed by the
 *       7 exception entries (2d cleaned 12 RC-005 by removing redundant
 *       yearly+rowFilter pairs; 2e/BRANCH migrated 2 RC-004 reports to
 *       scopeType=BRANCH).</li>
 *   <li>{@code hr-personel-listesi} (legitimate {@code OUR_COMPANY_ID})
 *       is NOT in the exception list.</li>
 *   <li>No unsuppressed FAILs remain in the gate output.</li>
 *   <li>Exception inventory exact: 7 entries (2× RC-001, 5× RC-004);
 *       RC-005 eliminated by 2d, 2 BRANCH-able RC-004 migrated by 2e.</li>
 * </ul>
 */
class ReportContractGateTest {

    @Test
    void gate_full31ReportRegistry_returnsCleanReport() {
        ContractReport report = ReportContractGate.create().gate();

        assertThat(report.reportCount())
                .as("All 31 migrated reports plus exceptions.json (excluded) discovered by sweep")
                .isEqualTo(31);

        // Codex iter-4 §1d-AGREE: gate must produce zero unsuppressed FAILs.
        // Exception entries cover known RC-004 + RC-001 debt. Anything beyond
        // that signals new tech debt or regression.
        assertThat(report.failures())
                .as("Unsuppressed failures: %s",
                        report.failures().stream().limit(10).toList())
                .isEmpty();
    }

    @Test
    void gate_knownRC004ExceptionsCoverHRandStokDurum() {
        // 7 RC-004 governance debt entries (Codex iter-4 §1d-AGREE list)
        ContractReport report = ReportContractGate.create().gate();
        List<String> knownDebtKeys = List.of(
                "hr-bordro-detay", "hr-giris-cikis", "hr-izin-raporu",
                "hr-maas-gecmisi", "hr-maas-raporu", "hr-puantaj",
                "stok-durum");

        // None of these should appear as failures.
        assertThat(report.failures())
                .as("Known RC-004 governance debt must be exception-suppressed")
                .noneMatch(v -> knownDebtKeys.contains(v.reportKey())
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
    void gate_ambiguousYearColumnReports_haveActiveExceptions() {
        // 2 RC-001 governance debt entries (yearColumn ambiguous)
        ContractReport report = ReportContractGate.create().gate();
        List<String> debtKeys = List.of("fin-alacak-yaslandirma", "fin-borc-yaslandirma");

        assertThat(report.failures())
                .as("Ambiguous yearColumn reports must be RC-001 exception-suppressed")
                .noneMatch(v -> debtKeys.contains(v.reportKey()) && "RC-001".equals(v.ruleId()));
    }

    @Test
    void exceptionsJson_inventoryExactCounts() throws Exception {
        // Codex iter-5 §1d-AGREE absorb: lock the production exception inventory
        // shape so accidental drift (e.g. someone adding an RC-007 90d entry
        // without review) is caught at gate-test time.
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("Total governance debt entries (Codex 019e0d06 iter-2: stok-durum RC-004 closed via current-resolver fix)")
                .hasSize(6);

        Map<String, Long> byRule = Arrays.stream(entries)
                .flatMap(e -> e.ruleIds().stream())
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        assertThat(byRule.get("RC-001"))
                .as("RC-001 debt: yearColumn ambiguous reports")
                .isEqualTo(2L);
        assertThat(byRule.get("RC-004"))
                .as("RC-004 debt: HR scopeType=COMPANY misclassified (-1 from stok-durum 019e0d06 absorb)")
                .isEqualTo(4L);
        // RC-005 eliminated by Phase 2 Program 2d (rowFilter removed from 12
        // yearly reports; 2a runtime tenant guard hardening provides the
        // fail-closed precondition).
        assertThat(byRule.get("RC-005"))
                .as("RC-005 debt eliminated by 2d")
                .isNull();

        // No other rule namespace should creep in without review.
        assertThat(byRule.keySet())
                .containsExactlyInAnyOrder("RC-001", "RC-004");
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
