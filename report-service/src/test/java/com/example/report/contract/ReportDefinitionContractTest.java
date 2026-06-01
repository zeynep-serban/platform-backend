package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractGateSummary;
import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.FilterDefinition;
import com.example.report.registry.FilterKind;
import com.example.report.registry.FilterOptionEntry;
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
    @DisplayName("Aggregate: 34 reports discovered (drift guard) — PR-D2.2 access-report added")
    void aggregate_thirtyTwoReportsDiscovered() {
        // PR-D2.1d (ADR-0015, Codex 019e83bd iter-2 PARTIAL absorb):
        // bumped 32 → 33 for users-overview (first LIVE remote-http report,
        // execution.kind=remote-http, service=user-service, path=/api/v1/users).
        // PR-D2.2 (ADR-0015, Codex 019e83f0 PARTIAL absorb):
        // bumped 33 → 34 for access-report (second LIVE remote-http report,
        // execution.kind=remote-http, service=permission-service, path=/api/v1/roles).
        assertThat(CACHED.reportCount()).isEqualTo(34);
    }

    @Test
    @DisplayName("Aggregate: zero raw violations, zero suppressions — all debt rule-closed")
    void aggregate_suppressedCountsMatchInventory() {
        // Codex 019e3f5c absorb: all report-contract governance debt is closed
        // at the rule level — RC-001 via the COMPANY_REMAINDER carve-out (#248),
        // RC-004 via the BRANCH migration + RC-004 v2 sourceQuery projected-join
        // boundary (#247). exceptions.json is empty: zero raw violations means
        // zero suppressions, and the gate is green without any exception.
        assertThat(CACHED.rawViolations())
                .as("raw violations: %s", CACHED.rawViolations())
                .isEmpty();
        assertThat(CACHED.suppressedByRule()).isEmpty();
        assertThat(CACHED.exceptionInventory()).isEmpty();
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
        // load all 33 known keys; exceptions.json is excluded as a non-report
        // (handled by ExceptionsRegistry; ReportRegistry log-swallows the
        // bind failure since it doesn't match ReportDefinition shape).
        // PR-D2.1d: bumped 32 → 33 for users-overview (first remote-http report).
        // Test uses classpath*:reports/ (multi-entry enumeration) because
        // Surefire fork classpath includes both target/classes + target/test-classes.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        assertThat(registry.getAll())
                .as("Runtime registry must load 34 reports")
                .hasSize(34);
        assertThat(registry.get("hr-personel-listesi")).isPresent();
        assertThat(registry.get("fin-fatura-satirlari")).isPresent();
        assertThat(registry.get("users-overview")).isPresent();  // PR-D2.1d
        assertThat(registry.get("access-report")).isPresent();   // PR-D2.2
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

        // PR-D2a (Codex thread 019e81fd, 2026-06-01) — backend metadata-prep
        // for the first static→dynamic migration target. Pins the column
        // type widening + filter definitions + identity carry that PR-D2b
        // (frontend hybrid) will consume.
        ColumnDefinition fullName = def.columns().stream()
                .filter(c -> "FULL_NAME".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(fullName.type()).isEqualTo("bold-text");

        ColumnDefinition gender = def.columns().stream()
                .filter(c -> "GENDER".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(gender.type()).isEqualTo("badge");
        assertThat(gender.variantMap()).containsEntry("Kadın", "primary");
        assertThat(gender.defaultVariant()).isEqualTo("muted");

        ColumnDefinition employmentType = def.columns().stream()
                .filter(c -> "EMPLOYMENT_TYPE".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(employmentType.type()).isEqualTo("badge");
        assertThat(employmentType.variantMap()).containsEntry("Tam Zamanlı", "success");
        assertThat(employmentType.defaultVariant()).isEqualTo("muted");

        ColumnDefinition tenureYears = def.columns().stream()
                .filter(c -> "TENURE_YEARS".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(tenureYears.suffix()).isEqualTo("yıl");

        ColumnDefinition hireDate = def.columns().stream()
                .filter(c -> "HIRE_DATE".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(hireDate.format()).isEqualTo("short");

        // EDUCATION + GENERATION stay text per D0 §2.7 (lowest-risk scope).
        ColumnDefinition education = def.columns().stream()
                .filter(c -> "EDUCATION".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(education.type()).isEqualTo("text");

        ColumnDefinition generation = def.columns().stream()
                .filter(c -> "GENERATION".equals(c.field()))
                .findFirst().orElseThrow();
        assertThat(generation.type()).isEqualTo("text");

        // PR-D2a identity carry — preserves favorites + saved-filter scope
        // for D2b/D3 when the dynamic catalog eventually owns the route.
        assertThat(def.sharedReportId()).isEqualTo("hr-demografik-yapi");

        // PR-D2a filterDefinitions — 5 entries, ordered, with search.targetField
        // pointed at FULL_NAME so the D1b translator emits a real column filter
        // (without targetField, search would resolve to a phantom `search` column).
        assertThat(def.filterDefinitions()).hasSize(5);
        assertThat(def.filterDefinitions().stream().map(FilterDefinition::key).toList())
                .containsExactly("search", "department", "location", "gender", "employmentType");

        FilterDefinition search = def.filterDefinitions().get(0);
        assertThat(search.kind()).isEqualTo(FilterKind.TEXT_SEARCH);
        assertThat(search.targetField()).isEqualTo("FULL_NAME");
        assertThat(search.operator()).isEqualTo("contains");

        FilterDefinition department = def.filterDefinitions().get(1);
        assertThat(department.kind()).isEqualTo(FilterKind.TEXT_SEARCH);
        assertThat(department.targetField()).isEqualTo("DEPARTMENT_NAME");

        FilterDefinition location = def.filterDefinitions().get(2);
        assertThat(location.targetField()).isEqualTo("LOCATION");

        FilterDefinition genderFilter = def.filterDefinitions().get(3);
        assertThat(genderFilter.kind()).isEqualTo(FilterKind.ENUM_SELECT);
        assertThat(genderFilter.targetField()).isEqualTo("GENDER");
        assertThat(genderFilter.options()).hasSize(3);

        FilterDefinition employmentTypeFilter = def.filterDefinitions().get(4);
        assertThat(employmentTypeFilter.kind()).isEqualTo(FilterKind.ENUM_SELECT);
        assertThat(employmentTypeFilter.targetField()).isEqualTo("EMPLOYMENT_TYPE");
        assertThat(employmentTypeFilter.options()).hasSize(3);

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

    @Test
    @DisplayName("hr-compensation-detay: PR-D3a metadata-prep contract")
    void hrCompensationDetay_PRD3aContract() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportRegistry registry = new ReportRegistry(mapper, "classpath*:reports/");
        registry.loadDefinitions();

        ReportDefinition def = registry.get("hr-compensation-detay").orElseThrow();

        // Column count + exact order pinned (19 columns).
        assertThat(def.columns().stream().map(ColumnDefinition::field).toList())
                .containsExactly(
                        "EMPLOYEE_ID", "FULL_NAME", "DEPARTMENT_NAME", "POSITION_NAME",
                        "BRANCH_NAME", "COMPANY_NAME", "COLLAR_TYPE", "GENDER",
                        "EDUCATION", "TENURE_YEARS", "AGE", "GROSS_SALARY",
                        "NET_SALARY", "TOTAL_EMPLOYER_COST", "SSK_EMPLOYER",
                        "INCOME_TAX", "OVERTIME_PAY", "SEVERANCE_AMOUNT", "IS_CRITICAL");

        // PR-D3a column widening per Codex thread 019e8282 iter-3 AGREE.
        ColumnDefinition fullName = def.columns().stream()
                .filter(c -> "FULL_NAME".equals(c.field())).findFirst().orElseThrow();
        assertThat(fullName.type()).isEqualTo("bold-text");

        // COLLAR_TYPE: badge + variantMap (static parity: 1=warning, 2=info) +
        // labelMap i18n keys + defaultVariant=muted.
        ColumnDefinition collarType = def.columns().stream()
                .filter(c -> "COLLAR_TYPE".equals(c.field())).findFirst().orElseThrow();
        assertThat(collarType.type()).isEqualTo("badge");
        assertThat(collarType.variantMap())
                .containsEntry("1", "warning")
                .containsEntry("2", "info");
        assertThat(collarType.labelMap())
                .containsEntry("1", "reports.hrCompensation.collarType.blue")
                .containsEntry("2", "reports.hrCompensation.collarType.white");
        assertThat(collarType.defaultVariant()).isEqualTo("muted");

        // GENDER: badge + static parity (0=Kadın=primary, 1=Erkek=info).
        ColumnDefinition gender = def.columns().stream()
                .filter(c -> "GENDER".equals(c.field())).findFirst().orElseThrow();
        assertThat(gender.type()).isEqualTo("badge");
        assertThat(gender.variantMap())
                .containsEntry("0", "primary")
                .containsEntry("1", "info");
        assertThat(gender.labelMap())
                .containsEntry("0", "reports.hrCompensation.gender.female")
                .containsEntry("1", "reports.hrCompensation.gender.male");
        assertThat(gender.defaultVariant()).isEqualTo("muted");

        // EDUCATION stays text per D0 §2.7 lowest-risk scope.
        ColumnDefinition education = def.columns().stream()
                .filter(c -> "EDUCATION".equals(c.field())).findFirst().orElseThrow();
        assertThat(education.type()).isEqualTo("text");

        // TENURE_YEARS: number + suffix "yıl" (parity with hr-demografik).
        ColumnDefinition tenureYears = def.columns().stream()
                .filter(c -> "TENURE_YEARS".equals(c.field())).findFirst().orElseThrow();
        assertThat(tenureYears.suffix()).isEqualTo("yıl");

        // 7 salary columns: currency + TRY + decimals=0 + sensitive=true.
        java.util.List<String> currencyFields = java.util.List.of(
                "GROSS_SALARY", "NET_SALARY", "TOTAL_EMPLOYER_COST",
                "SSK_EMPLOYER", "INCOME_TAX", "OVERTIME_PAY", "SEVERANCE_AMOUNT");
        for (String field : currencyFields) {
            ColumnDefinition col = def.columns().stream()
                    .filter(c -> field.equals(c.field())).findFirst().orElseThrow();
            assertThat(col.type()).as("%s.type", field).isEqualTo("currency");
            assertThat(col.currencyCode()).as("%s.currencyCode", field).isEqualTo("TRY");
            assertThat(col.decimals()).as("%s.decimals", field).isEqualTo(0);
            assertThat(col.sensitive()).as("%s.sensitive", field).isTrue();
        }

        // IS_CRITICAL boolean (schema-safe; trueLabelKey/falseLabelKey
        // NOT in backend contract per additionalProperties=false).
        ColumnDefinition isCritical = def.columns().stream()
                .filter(c -> "IS_CRITICAL".equals(c.field())).findFirst().orElseThrow();
        assertThat(isCritical.type()).isEqualTo("boolean");

        // Identity carry — preserves favorites + saved-filter scope for
        // the PR-D2b hybrid wrapper when the dynamic catalog fuses with
        // the static hr-compensation-report module at route `hr-compensation`.
        assertThat(def.routeSegment()).isEqualTo("hr-compensation");
        assertThat(def.sharedReportId()).isEqualTo("hr-compensation");

        // defaultSort preserved (UX policy unchanged in D3a).
        assertThat(def.defaultSort()).isEqualTo("GROSS_SALARY");
        assertThat(def.defaultSortDirection()).isEqualTo("DESC");

        // Access preserved (CRITICAL — auth/sensitive salary surface).
        assertThat(def.access().permission()).isEqualTo("reports.hr-compensation-detay.view");
        assertThat(def.access().reportGroup()).isEqualTo("HR_REPORTS");
        assertThat(def.access().columnRestrictions())
                .containsKey("reports.hr.salary-view");
        assertThat(def.access().columnRestrictions().get("reports.hr.salary-view"))
                .containsExactly("GROSS_SALARY", "NET_SALARY", "TOTAL_EMPLOYER_COST",
                                 "SSK_EMPLOYER", "INCOME_TAX", "OVERTIME_PAY", "SEVERANCE_AMOUNT");

        // filterDefinitions: 6 entries ordered, aligned with HrCompensationFilters.
        assertThat(def.filterDefinitions()).hasSize(6);
        assertThat(def.filterDefinitions().stream().map(FilterDefinition::key).toList())
                .containsExactly("search", "department", "company",
                                 "collarType", "gender", "education");

        FilterDefinition search = def.filterDefinitions().get(0);
        assertThat(search.kind()).isEqualTo(FilterKind.TEXT_SEARCH);
        assertThat(search.targetField()).isEqualTo("FULL_NAME");

        FilterDefinition collarFilter = def.filterDefinitions().get(3);
        assertThat(collarFilter.kind()).isEqualTo(FilterKind.ENUM_SELECT);
        assertThat(collarFilter.targetField()).isEqualTo("COLLAR_TYPE");
        assertThat(collarFilter.options()).hasSize(2);
        assertThat(collarFilter.options().get(0).value()).isEqualTo("1");
        assertThat(collarFilter.options().get(0).label()).isEqualTo("Mavi Yakalı");

        FilterDefinition genderFilter = def.filterDefinitions().get(4);
        assertThat(genderFilter.options()).hasSize(2);

        // EDUCATION enum-select with 10 options, ASCII values matching SQL
        // CASE output (Codex iter-2: SQL transliteration is ASCII, not Unicode).
        FilterDefinition educationFilter = def.filterDefinitions().get(5);
        assertThat(educationFilter.kind()).isEqualTo(FilterKind.ENUM_SELECT);
        assertThat(educationFilter.targetField()).isEqualTo("EDUCATION");
        assertThat(educationFilter.options()).hasSize(10);
        assertThat(educationFilter.options().stream().map(FilterOptionEntry::value).toList())
                .containsExactly("Ilkokul", "Ortaokul", "Lise", "Meslek Lisesi",
                                 "Onlisans", "Lisans", "Muhendislik", "Fakulte",
                                 "Yuksek Lisans", "Doktora");
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
        // Drift guard: exact 34-key list matches registry inventory at
        // commit-time. New report → add here; missing report → fail.
        // PR-D2.1d: added "users-overview" (first remote-http report).
        // PR-D2.2: added "access-report" (second remote-http report).
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
                "hr-puantaj", "satis-ozet", "stok-durum",
                "users-overview", "access-report");
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
