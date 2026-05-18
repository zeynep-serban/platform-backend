package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractGateSummary;
import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 2 Program 1e — Final report contract gate test.
 *
 * <p>Codex iter-7 §1e-AGREE absorb (thread 019e0119): hybrid model — single
 * cached gate run via {@code @BeforeAll}, then aggregate + 32 per-report
 * parameterized assertions consume the cached summary. Also writes
 * {@code report.json} + {@code comment.md} artifacts to
 * {@code report-service/target/report-contract-gate/} for the GitHub
 * Actions workflow to upload + Marocchino sticky comment.
 *
 * <p>Acceptance:
 * <ul>
 *   <li>Aggregate: zero unsuppressed failures, zero meta failures.</li>
 *   <li>Per-report: each known report key has zero unsuppressed failures.</li>
 *   <li>Artifacts: report.json + comment.md present after gate run.</li>
 * </ul>
 */
@Tag("contract-gate")
class ReportDefinitionContractTest {

    /**
     * Fixed clock for deterministic Markdown expiry-day calculations across
     * test runs. 2026-05-07 = governance debt creation date; 90d horizon
     * → 2026-08-05 expiresAt → ~89 days remaining.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    private static ContractGateSummary CACHED;

    @BeforeAll
    static void runGateOnce() throws IOException {
        CACHED = ReportContractGate.create(FIXED_CLOCK).gateDetailed();
        writeArtifacts(CACHED);
    }

    @Test
    @DisplayName("Aggregate: zero unsuppressed failures, zero meta failures")
    void aggregate_zeroUnsuppressedFailures() {
        assertThat(CACHED.unsuppressedFailures())
                .as("Unsuppressed failures: %s",
                        CACHED.unsuppressedFailures().stream().limit(5).toList())
                .isEmpty();
        assertThat(CACHED.metaFailures())
                .as("Meta failures: %s",
                        CACHED.metaFailures().stream().limit(5).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("Aggregate: 32 reports discovered (drift guard)")
    void aggregate_thirtyTwoReportsDiscovered() {
        assertThat(CACHED.reportCount()).isEqualTo(32);
    }

    @Test
    @DisplayName("Aggregate: suppressed counts match exception inventory (post-2d + 2e/BRANCH)")
    void aggregate_suppressedCountsMatchInventory() {
        // Phase 2 Program 2d cleaned RC-005×12 (rowFilter removed from 12 yearly
        // reports; 2a runtime tenant guard provides fail-closed precondition).
        // Phase 2 Program 2e/BRANCH migrated 2 reports (hr-giris-cikis +
        // hr-puantaj) from scopeType=COMPANY to scopeType=BRANCH (legitimate
        // BRANCH boundary; permission-service vocabulary already supports it).
        // Codex 019e0d06 iter-2 absorb: stok-durum RC-004 entry closed via
        // current-resolver fix (rowFilter removed; ORDER_ID was order-level,
        // not tenant; schema-level isolation now via current resolver).
        // Remaining: RC-001×2 (yearColumn ambiguous), RC-004×4 (DEPT/EMPLOYEE debt).
        assertThat(CACHED.suppressedByRule())
                .containsEntry("RC-001", 2L)
                .containsEntry("RC-004", 4L)
                .doesNotContainKey("RC-005");
    }

    @Test
    @DisplayName("Artifacts: report.json + comment.md exist + non-empty")
    void artifacts_existAndNonEmpty() {
        Path jsonPath = artifactsDir().resolve("report.json");
        Path mdPath = artifactsDir().resolve("comment.md");

        assertThat(jsonPath).exists();
        assertThat(mdPath).exists();
        try {
            assertThat(Files.size(jsonPath)).isGreaterThan(100L);
            assertThat(Files.size(mdPath)).isGreaterThan(100L);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read artifact size: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("ReportRegistry: 32 reports loadable + exceptions.json excluded")
    void reportRegistry_loadableAcceptanceTest() {
        // Codex iter-3 §1c absorb (carry-forward to 1e): runtime registry must
        // load all 32 known keys; exceptions.json is excluded as a non-report
        // (handled by ExceptionsRegistry; ReportRegistry log-swallows the
        // bind failure since it doesn't match ReportDefinition shape).
        // Test uses classpath*:reports/ (multi-entry enumeration) because
        // Surefire fork classpath includes both target/classes + target/test-classes.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        assertThat(registry.getAll())
                .as("Runtime registry must load 32 reports")
                .hasSize(32);
        assertThat(registry.get("hr-personel-listesi")).isPresent();
        assertThat(registry.get("fin-fatura-satirlari")).isPresent();
        assertThat(registry.get("exceptions")).isEmpty();  // excluded
    }

    @Test
    @DisplayName("fin-muhasebe-detay: BA column displays Borç/Alacak labels")
    void finMuhasebeDetay_baColumnDisplaysDebitCreditLabels() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        ReportDefinition def = registry.get("fin-muhasebe-detay").orElseThrow();
        ColumnDefinition baColumn = def.columns().stream()
                .filter(c -> "BA".equals(c.field()))
                .findFirst()
                .orElseThrow();

        assertThat(baColumn.headerName()).isEqualTo("Borç/Alacak");
        assertThat(baColumn.type()).isEqualTo("text");
        assertThat(def.sourceQuery())
                .contains("CASE WHEN ACR.BA = 1 THEN N'Borç'")
                .contains("WHEN ACR.BA = 0 THEN N'Alacak'")
                .contains("END AS BA");
    }

    @Test
    @DisplayName("fin-muhasebe-detay: ACCOUNT_CARD_ROWS amount_2 fields are exposed")
    void finMuhasebeDetay_accountCardRowsAmount2FieldsAreExposed() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        ReportDefinition def = registry.get("fin-muhasebe-detay").orElseThrow();
        List<String> fields = def.columns().stream()
                .map(ColumnDefinition::field)
                .toList();

        assertThat(fields)
                .containsSubsequence(
                        "AMOUNT",
                        "AMOUNT_CURRENCY",
                        "AMOUNT_2",
                        "AMOUNT_CURRENCY_2",
                        "OTHER_AMOUNT",
                        "OTHER_CURRENCY");

        ColumnDefinition amount2 = def.columns().stream()
                .filter(c -> "AMOUNT_2".equals(c.field()))
                .findFirst()
                .orElseThrow();
        ColumnDefinition amountCurrency2 = def.columns().stream()
                .filter(c -> "AMOUNT_CURRENCY_2".equals(c.field()))
                .findFirst()
                .orElseThrow();

        assertThat(amount2.headerName()).isEqualTo("Tutar 2");
        assertThat(amount2.type()).isEqualTo("number");
        assertThat(amount2.aggregatable()).isTrue();
        assertThat(amount2.defaultAggFunc()).isEqualTo("sum");
        assertThat(amountCurrency2.headerName()).isEqualTo("Para Birimi 2");
        assertThat(amountCurrency2.type()).isEqualTo("text");
        assertThat(amountCurrency2.groupable()).isTrue();
        assertThat(def.sourceQuery())
                .contains("ACR.AMOUNT_2")
                .contains("ACR.AMOUNT_CURRENCY_2");
    }

    @Test
    @DisplayName("fin-muhasebe-detay: amount fields are Borç positive and Alacak negative")
    void finMuhasebeDetay_amountFieldsAreDebitPositiveCreditNegative() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        ReportDefinition def = registry.get("fin-muhasebe-detay").orElseThrow();
        ColumnDefinition amount = def.columns().stream()
                .filter(c -> "AMOUNT".equals(c.field()))
                .findFirst()
                .orElseThrow();

        assertThat(amount.aggregatable()).isTrue();
        assertThat(amount.defaultAggFunc()).isEqualTo("sum");
        assertThat(def.sourceQuery())
                .contains("CASE WHEN ACR.BA = 1 THEN ABS(ACR.AMOUNT) "
                        + "WHEN ACR.BA = 0 THEN -ABS(ACR.AMOUNT) "
                        + "ELSE ACR.AMOUNT END AS AMOUNT")
                .contains("CASE WHEN ACR.BA = 1 THEN ABS(ACR.AMOUNT_2) "
                        + "WHEN ACR.BA = 0 THEN -ABS(ACR.AMOUNT_2) "
                        + "ELSE ACR.AMOUNT_2 END AS AMOUNT_2")
                .contains("CASE WHEN ACR.BA = 1 THEN ABS(ACR.OTHER_AMOUNT) "
                        + "WHEN ACR.BA = 0 THEN -ABS(ACR.OTHER_AMOUNT) "
                        + "ELSE ACR.OTHER_AMOUNT END AS OTHER_AMOUNT");
    }

    @Test
    @DisplayName("hr-demografik-yapi: live Workcube demographic grid contract")
    void hrDemografikYapi_liveWorkcubeContract() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        ReportDefinition def = registry.get("hr-demografik-yapi").orElseThrow();

        // Column contract — the 12 fields the hand-written
        // hr-demographic-report module maps onto HrDemographicRow.
        assertThat(def.columns().stream().map(ColumnDefinition::field).toList())
                .containsExactly(
                        "EMPLOYEE_ID", "FULL_NAME", "DEPARTMENT_NAME", "POSITION_NAME",
                        "GENDER", "AGE", "EDUCATION", "EMPLOYMENT_TYPE", "LOCATION",
                        "HIRE_DATE", "TENURE_YEARS", "GENERATION");

        ColumnDefinition generation = def.columns().stream()
                .filter(c -> "GENERATION".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(generation.type()).isEqualTo("text");

        // Codex 019e3b64 B1: no phantom per-report permission — access gates
        // by REPORT_VIEW + HR_REPORTS group only (the permission catalog has
        // no reports.hr-demografik-yapi.view grant).
        assertThat(def.access().permission()).isNull();
        assertThat(def.access().reportGroup()).isEqualTo("HR_REPORTS");

        // sourceQuery: one row per currently-employed employee (ROW_NUMBER
        // dedup over active EMPLOYEES_IN_OUT) + N'...' NVARCHAR literals so
        // Turkish labels survive the MSSQL codepage (Codex 019e3b64 amend).
        assertThat(def.sourceQuery())
                .contains("ROW_NUMBER() OVER (PARTITION BY eio.EMPLOYEE_ID")
                .contains("N'Belirtilmemiş'")
                .contains("N'Kadın'");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownReportKeys")
    @DisplayName("Per-report: zero unsuppressed failures")
    void perReport_zeroUnsuppressedFailures(String reportKey) {
        List<ContractViolation> failures = CACHED.unsuppressedFailuresFor(reportKey);
        assertThat(failures)
                .as("Report '%s' unsuppressed failures: %s", reportKey, failures)
                .isEmpty();
    }

    static Stream<String> knownReportKeys() {
        // Drift guard: exact 32-key list matches registry inventory at
        // commit-time. New report → add here; missing report → fail.
        return Stream.of(
                "fin-alacak-yaslandirma", "fin-banka-hareketleri", "fin-borc-yaslandirma",
                "fin-butce-gerceklesen", "fin-cari-hareketler", "fin-cari-islemler",
                "fin-cari-mutabakat", "fin-cek-senet", "fin-cek-vade-takip",
                "fin-fatura-satirlari", "fin-faturalar", "fin-gerceklesen-maliyet",
                "fin-kasa-hareketleri", "fin-kaynak-eslesme", "fin-masraf-detay",
                "fin-muhasebe-detay", "fin-muhasebe-fisleri", "fin-nakit-akis-ozet",
                "fin-stok-fis-detay", "fin-tutar-mutabakat",
                "hr-bordro-detay", "hr-compensation-detay", "hr-demografik-yapi",
                "hr-egitim-katilim", "hr-giris-cikis", "hr-izin-raporu",
                "hr-maas-gecmisi", "hr-maas-raporu", "hr-personel-listesi",
                "hr-puantaj", "satis-ozet", "stok-durum");
    }

    private static void writeArtifacts(ContractGateSummary summary) throws IOException {
        Path dir = artifactsDir();
        Files.createDirectories(dir);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Files.writeString(dir.resolve("report.json"), summary.toJson(mapper));
        Files.writeString(dir.resolve("comment.md"), summary.toMarkdown(FIXED_CLOCK));
    }

    private static Path artifactsDir() {
        // Resolve relative to module root (Surefire CWD).
        return Paths.get("target", "report-contract-gate");
    }
}
