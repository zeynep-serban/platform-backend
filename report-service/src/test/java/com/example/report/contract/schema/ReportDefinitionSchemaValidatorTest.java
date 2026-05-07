package com.example.report.contract.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 Program 1c — ReportDefinitionSchemaValidator unit tests.
 *
 * <p>Codex iter-2 absorb (thread 019e0119): minimum acceptance scenarios:
 * <ul>
 *   <li>Missing required field → REPORT_SCHEMA_INVALID + JSON Pointer field</li>
 *   <li>Invalid column type enum → field pointer doğru</li>
 *   <li>Schema-valid migrated reports yield empty violation list</li>
 *   <li>Malformed JSON → REPORT_FILE_LOAD_ERROR (parse fail before schema)</li>
 * </ul>
 */
class ReportDefinitionSchemaValidatorTest {

    private ReportDefinitionSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReportDefinitionSchemaValidator(
                new ObjectMapper().findAndRegisterModules(),
                "classpath:contract/report-definition.schema.json",
                new DefaultResourceLoader());
    }

    @Test
    void validate_missingContractVersion_surfacesSchemaInvalid() {
        String json = "{\"key\":\"r-x\",\"version\":\"1.0\",\"title\":\"X\","
                + "\"category\":\"Finans\",\"source\":\"T\",\"sourceSchema\":\"s\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"no tenant scoping\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-x.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("contractVersion"));
    }

    @Test
    void validate_invalidColumnType_surfacesPointerToColumnsIndex() {
        String json = "{\"contractVersion\":1,\"key\":\"r-x\",\"version\":\"1.0\",\"title\":\"X\","
                + "\"category\":\"Finans\",\"source\":\"T\",\"sourceSchema\":\"s\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"no tenant scoping\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"INVALID_TYPE\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-x.json");

        // networknt JsonSchemaFactory default formats instance location as
        // JSONPath ($.field[idx].sub) rather than RFC-6901 JSON Pointer.
        // The path content (columns/0/type) remains stable for PR feedback.
        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("columns")
                        && v.field().contains("0")
                        && v.field().contains("type"));
    }

    @Test
    void validate_invalidSchemaModeStandard_surfacesEnumViolation() {
        // Codex iter-2 absorb: standard schemaMode YASAK; enum yearly/current/canonical/static
        String json = "{\"contractVersion\":1,\"key\":\"r-x\",\"version\":\"1.0\",\"title\":\"X\","
                + "\"category\":\"Finans\",\"source\":\"T\",\"sourceSchema\":\"s\","
                + "\"schemaMode\":\"standard\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"no tenant scoping\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-x.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("schemaMode"));
    }

    @Test
    void validate_missingSourceAndSourceQuery_surfacesAnyOfViolation() {
        String json = "{\"contractVersion\":1,\"key\":\"r-x\",\"version\":\"1.0\",\"title\":\"X\","
                + "\"category\":\"Finans\",\"sourceSchema\":\"s\",\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"no tenant scoping\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-x.json");

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> "REPORT_SCHEMA_INVALID".equals(v.ruleId()));
    }

    @Test
    void validate_tenantBoundaryReasonTooShort_surfacesMinLengthViolation() {
        String json = "{\"contractVersion\":1,\"key\":\"r-x\",\"version\":\"1.0\",\"title\":\"X\","
                + "\"category\":\"Finans\",\"source\":\"T\",\"sourceSchema\":\"s\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"x\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-x.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("tenantBoundary")
                        && v.field().contains("reason"));
    }

    @Test
    void validate_validReport_returnsEmpty() {
        String json = "{\"contractVersion\":1,\"key\":\"r-valid\",\"version\":\"1.0\","
                + "\"title\":\"Valid\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-valid.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_malformedJson_surfacesFileLoadError() {
        List<ContractViolation> violations = validator.validate("not valid json {", "broken.json");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("REPORT_FILE_LOAD_ERROR");
        assertThat(violations.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
    }

    @Test
    void validate_emptyJson_surfacesFileLoadError() {
        List<ContractViolation> violations = validator.validate("", "empty.json");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("REPORT_FILE_LOAD_ERROR");
    }

    @Test
    void validate_reportKeyTakenFromJsonNotFilePath() {
        // Verifies resolveReportKey: prefer JSON-declared key over file name path
        String json = "{\"contractVersion\":1,\"key\":\"actual-key\",\"version\":\"1.0\","
                + "\"title\":\"X\",\"category\":\"Finans\","
                + "\"source\":\"T\",\"sourceSchema\":\"s\",\"schemaMode\":\"INVALID\","
                + "\"tenantBoundary\":{\"mode\":\"none\",\"scopeType\":\"global\",\"reason\":\"no tenant scoping\"},"
                + "\"columns\":[{\"field\":\"A\",\"headerName\":\"A\",\"type\":\"text\"}]}";

        List<ContractViolation> violations = validator.validate(json, "wrong-name.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && "actual-key".equals(v.reportKey()));
    }
}
