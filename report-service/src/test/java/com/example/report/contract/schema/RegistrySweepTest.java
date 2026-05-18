package com.example.report.contract.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 Program 1c — RegistrySweep tests covering sweep-level meta-rules:
 * <ul>
 *   <li>{@code REPORT_KEY_DUPLICATE} — 2 files share key</li>
 *   <li>{@code REPORT_FILE_LOAD_ERROR} — single file parse fail</li>
 *   <li>Schema violations from individual files surface via sweep</li>
 *   <li>Built-in 32 reports schema-valid (Codex iter-2 minimum acceptance)</li>
 * </ul>
 */
class RegistrySweepTest {

    @Test
    void sweep_duplicateKeyAcrossTwoFiles_surfacesDuplicateViolation(@TempDir Path tempDir) throws IOException {
        // Two files with same key=test-dup → REPORT_KEY_DUPLICATE
        String validReport = """
                {"contractVersion":1,"key":"test-dup","version":"1.0",
                 "title":"X","category":"Finans","source":"T","sourceSchema":"s",
                 "schemaMode":"static",
                 "tenantBoundary":{"mode":"none","scopeType":"global","reason":"test fixture for duplicate"},
                 "columns":[{"field":"A","headerName":"A","type":"text"}]}
                """;
        Files.writeString(tempDir.resolve("a.json"), validReport);
        Files.writeString(tempDir.resolve("b.json"), validReport);

        RegistrySweep sweep = new RegistrySweep(
                new ObjectMapper().findAndRegisterModules(),
                schemaValidator(),
                "file:" + tempDir.toAbsolutePath() + "/*.json");

        List<ContractViolation> violations = sweep.sweep();

        assertThat(violations).anyMatch(v ->
                "REPORT_KEY_DUPLICATE".equals(v.ruleId())
                        && "test-dup".equals(v.reportKey())
                        && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void sweep_malformedFile_surfacesFileLoadError(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("broken.json"), "this is not json");

        RegistrySweep sweep = new RegistrySweep(
                new ObjectMapper().findAndRegisterModules(),
                schemaValidator(),
                "file:" + tempDir.toAbsolutePath() + "/*.json");

        List<ContractViolation> violations = sweep.sweep();

        assertThat(violations).anyMatch(v ->
                "REPORT_FILE_LOAD_ERROR".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void sweep_schemaInvalidFile_surfacesSchemaInvalidViolation(@TempDir Path tempDir) throws IOException {
        // Missing required contractVersion → REPORT_SCHEMA_INVALID
        String invalid = """
                {"key":"test-x","version":"1.0","title":"X","category":"Finans",
                 "source":"T","sourceSchema":"s","schemaMode":"static",
                 "tenantBoundary":{"mode":"none","scopeType":"global","reason":"test fixture missing contractVersion"},
                 "columns":[{"field":"A","headerName":"A","type":"text"}]}
                """;
        Files.writeString(tempDir.resolve("invalid.json"), invalid);

        RegistrySweep sweep = new RegistrySweep(
                new ObjectMapper().findAndRegisterModules(),
                schemaValidator(),
                "file:" + tempDir.toAbsolutePath() + "/*.json");

        List<ContractViolation> violations = sweep.sweep();

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && "test-x".equals(v.reportKey()));
    }

    @Test
    void sweep_excludesExceptionsJson(@TempDir Path tempDir) throws IOException {
        // exceptions.json must not be processed as a report
        String invalidIfTreatedAsReport = "[{\"id\":\"X\",\"ruleIds\":[],\"reportKey\":\"k\",\"reason\":\"r\",\"owner\":\"o\",\"expiresAt\":null}]";
        Files.writeString(tempDir.resolve("exceptions.json"), invalidIfTreatedAsReport);

        RegistrySweep sweep = new RegistrySweep(
                new ObjectMapper().findAndRegisterModules(),
                schemaValidator(),
                "file:" + tempDir.toAbsolutePath() + "/*.json");

        List<ContractViolation> violations = sweep.sweep();

        // No violations because exceptions.json is excluded
        assertThat(violations).noneMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId()) && "exceptions.json".equals(v.reportKey()));
    }

    @Test
    void sweep_builtinReports_areAllSchemaValid() {
        // Codex iter-2 minimum acceptance: 32/32 migrated reports schema-valid
        // AND no duplicate keys.
        RegistrySweep sweep = new RegistrySweep(
                new ObjectMapper().findAndRegisterModules(),
                schemaValidator(),
                "classpath:reports/*.json");

        List<ContractViolation> violations = sweep.sweep();

        assertThat(violations)
                .as("All 32 migrated reports must pass schema gate; %s",
                        violations.stream().limit(5).toList())
                .isEmpty();
    }

    private ReportDefinitionSchemaValidator schemaValidator() {
        return new ReportDefinitionSchemaValidator(
                new ObjectMapper().findAndRegisterModules(),
                "classpath:contract/report-definition.schema.json",
                new DefaultResourceLoader());
    }
}
