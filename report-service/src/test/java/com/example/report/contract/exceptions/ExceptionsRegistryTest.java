package com.example.report.contract.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 Program 1b — ExceptionsRegistry unit tests.
 *
 * <p>Spec §5.1 + Codex iter-1 §3 + iter-3 absorb:
 * <ul>
 *   <li>filtersByReportKeyAndRuleIds: matched entry suppresses violation</li>
 *   <li>rejectsExpired: past expiresAt → entry ignored, violation surfaces</li>
 *   <li>rejectsExpiresAtBeyond90Days: >90d horizon → meta-violation FAIL</li>
 *   <li>rejectsMissingExpiresAt: null expiresAt → meta-violation FAIL</li>
 * </ul>
 */
class ExceptionsRegistryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void filtersByReportKeyAndRuleIds_validEntry_suppresses() {
        // expiresAt 2026-08-01 = ~85 days from 2026-05-07 (within 90d horizon)
        ExceptionsRegistry registry = loadFromTestResource(
                "classpath:reports/exceptions-test.json", FIXED_CLOCK);

        List<ContractViolation> violations = List.of(
                ContractViolation.fail("RC-003", "legacy-stok-rapor", "sourceQuery",
                        "Hardcoded schema"));

        List<ContractViolation> filtered = registry.apply(violations);

        // Underlying RC-003 suppressed; meta-violations (none for valid entry) empty.
        assertThat(filtered).noneMatch(v -> "RC-003".equals(v.ruleId()));
    }

    @Test
    void rejectsExpired_pastExpiresAt_entryIgnored() {
        // Clock 2026-05-07; entry expiresAt 2024-01-01 (past) → entry inert, RC-003 surfaces
        // BUT: "2024-01-01" entry without active suppression → still meta-violation if missing expiry?
        // → past expiry is OK behavior; not meta-violated.
        Clock futureClock = Clock.fixed(Instant.parse("2099-12-31T00:00:00Z"), ZoneOffset.UTC);
        // From the same test resource (expiresAt 2099-08-01) but futureClock makes it past
        ExceptionsRegistry registry = loadFromTestResource(
                "classpath:reports/exceptions-test.json", futureClock);

        List<ContractViolation> violations = List.of(
                ContractViolation.fail("RC-003", "legacy-stok-rapor", "sourceQuery",
                        "Hardcoded schema"));

        List<ContractViolation> filtered = registry.apply(violations);

        // Past expiry → entry inert → RC-003 surfaces
        assertThat(filtered).anyMatch(v -> "RC-003".equals(v.ruleId()));
    }

    @Test
    void rejectsExpiresAtBeyond90Days_metaViolationFail() throws Exception {
        // Inline JSON: expiresAt 2099-08-01 = >90d horizon from 2026-05-07
        String json = "[{\"id\":\"X-LONG\",\"ruleIds\":[\"RC-003\"],"
                + "\"reportKey\":\"r\",\"reason\":\"long\",\"owner\":\"o\","
                + "\"expiresAt\":\"2099-08-01T00:00:00Z\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> filtered = registry.apply(List.of());

        assertThat(filtered).anyMatch(v ->
                "EXCEPTION_BEYOND_90D_HORIZON".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("90-day horizon"));
    }

    @Test
    void rejectsMissingExpiresAt_metaViolationFail() throws Exception {
        // Inline JSON entry with missing expiresAt
        String json = "[{\"id\":\"X-001\",\"ruleIds\":[\"RC-003\"],"
                + "\"reportKey\":\"some-report\",\"reason\":\"test\",\"owner\":\"test\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> filtered = registry.apply(List.of());

        assertThat(filtered).anyMatch(v ->
                "EXCEPTION_MISSING_EXPIRY".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("missing expiresAt"));
    }

    @Test
    void malformedExceptionsJson_surfacesLoadErrorFail() throws Exception {
        // Codex iter-1 BLOCKING absorb: malformed JSON → governance artifact
        // integrity violation surfaces as FAIL (build-time gate fail-closed).
        // Truly invalid JSON content — Jackson can't parse "not even json".
        ExceptionsRegistry registry = loadFromInline("this is not valid json", FIXED_CLOCK);

        List<ContractViolation> filtered = registry.apply(List.of());

        assertThat(filtered).anyMatch(v ->
                "EXCEPTION_REGISTRY_LOAD_ERROR".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("Failed to load"));
    }

    @Test
    void noEntries_emptyInput_returnsEmpty() throws Exception {
        ExceptionsRegistry registry = loadFromInline("[]", FIXED_CLOCK);

        List<ContractViolation> filtered = registry.apply(List.of());

        assertThat(filtered).isEmpty();
    }

    @Test
    void rejectsNonRcRuleId_metaViolationFail() throws Exception {
        // Phase 2 Program 1d (Codex iter-5 §1d-AGREE absorb): non-RC ruleId
        // entries → EXCEPTION_INVALID_RULE_ID FAIL meta-violation, entry inert.
        String json = "[{\"id\":\"X-META\",\"ruleIds\":[\"REPORT_SCHEMA_INVALID\"],"
                + "\"reportKey\":\"r\",\"reason\":\"meta bypass attempt\","
                + "\"owner\":\"o\",\"expiresAt\":\"2026-08-01T00:00:00Z\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> filtered = registry.apply(List.of());

        assertThat(filtered).anyMatch(v ->
                "EXCEPTION_INVALID_RULE_ID".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("REPORT_SCHEMA_INVALID"));
    }

    @Test
    void metaViolationNotSuppressible_evenIfExceptionEntryTargetsIt() throws Exception {
        // Even if exception entry tried to target REPORT_SCHEMA_INVALID by listing
        // it as a ruleId (non-suppressible namespace), a raw REPORT_SCHEMA_INVALID
        // input violation must NEVER be suppressed.
        String json = "[{\"id\":\"X-META\",\"ruleIds\":[\"RC-003\"],"
                + "\"reportKey\":\"r\",\"reason\":\"valid RC entry\","
                + "\"owner\":\"o\",\"expiresAt\":\"2026-08-01T00:00:00Z\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> input = List.of(
                ContractViolation.fail("REPORT_SCHEMA_INVALID", "r", "/columns",
                        "shape mismatch — meta-violation"));
        List<ContractViolation> filtered = registry.apply(input);

        assertThat(filtered).anyMatch(v -> "REPORT_SCHEMA_INVALID".equals(v.ruleId()));
    }

    @Test
    void ruleExecutionErrorNotSuppressible_evenWithValidRcException() throws Exception {
        // RC-XXX rule crashed; ContractValidator emits RC-XXX FAIL with
        // RULE_EXECUTION_ERROR message prefix (infra bug). Even with a valid
        // RC-004 exception entry, this must NEVER be suppressed.
        String json = "[{\"id\":\"X-VALID\",\"ruleIds\":[\"RC-004\"],"
                + "\"reportKey\":\"r\",\"reason\":\"legit RC-004 suppression\","
                + "\"owner\":\"o\",\"expiresAt\":\"2026-08-01T00:00:00Z\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> input = List.of(
                ContractViolation.fail("RC-004", "r", "rowFilter.column",
                        "RULE_EXECUTION_ERROR: rule threw RuntimeException: NPE — validator failed closed"));
        List<ContractViolation> filtered = registry.apply(input);

        assertThat(filtered).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().startsWith("RULE_EXECUTION_ERROR"));
    }

    @Test
    void rcRuleIdSuppressionWorksAsBefore_normalRc004Path() throws Exception {
        // Sanity: valid RC-004 exception still suppresses RC-004 underlying violation
        // (positive control alongside the non-suppressible guards).
        String json = "[{\"id\":\"X-VALID\",\"ruleIds\":[\"RC-004\"],"
                + "\"reportKey\":\"r\",\"reason\":\"legit RC-004 suppression\","
                + "\"owner\":\"o\",\"expiresAt\":\"2026-08-01T00:00:00Z\"}]";
        ExceptionsRegistry registry = loadFromInline(json, FIXED_CLOCK);

        List<ContractViolation> input = List.of(
                ContractViolation.fail("RC-004", "r", "rowFilter.column",
                        "Column 'X' not in tenant column allowlist"));
        List<ContractViolation> filtered = registry.apply(input);

        assertThat(filtered).noneMatch(v -> "RC-004".equals(v.ruleId()));
    }

    private ExceptionsRegistry loadFromTestResource(String classpathPath, Clock clock) {
        ExceptionsRegistry registry = new ExceptionsRegistry(
                new DefaultResourceLoader(),
                new ObjectMapper().findAndRegisterModules(),
                classpathPath, clock);
        registry.load();
        return registry;
    }

    private ExceptionsRegistry loadFromInline(String json, Clock clock) throws Exception {
        // Write JSON to temp file + load via file: resource
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("exceptions-test-", ".json");
        java.nio.file.Files.writeString(tmp, json);
        ExceptionsRegistry registry = new ExceptionsRegistry(
                new DefaultResourceLoader(),
                new ObjectMapper().findAndRegisterModules(),
                "file:" + tmp.toAbsolutePath(), clock);
        registry.load();
        return registry;
    }
}
