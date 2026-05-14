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

    /**
     * PR-0.4z (Codex 019e2695 review) — schema gate must accept the
     * three new aggregate tokens (distinctcount, stddev, stddevp) so
     * that registry-side opt-in does not get rejected by the raw JSON
     * schema check before Jackson even runs. median + percentile +
     * weightedAvg remain out of the enum (PR #6a / #6b / PR-0.4).
     */
    @Test
    void validate_distinctCountDefaultAggFunc_passesSchemaGate() {
        String json = "{\"contractVersion\":1,\"key\":\"r-distinctcount\",\"version\":\"1.0\","
                + "\"title\":\"DC\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"USER_ID\",\"headerName\":\"User\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"distinctcount\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-distinctcount.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_stddevDefaultAggFunc_passesSchemaGate() {
        String json = "{\"contractVersion\":1,\"key\":\"r-stddev\",\"version\":\"1.0\","
                + "\"title\":\"SD\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"stddev\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-stddev.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_stddevpDefaultAggFunc_passesSchemaGate() {
        String json = "{\"contractVersion\":1,\"key\":\"r-stddevp\",\"version\":\"1.0\","
                + "\"title\":\"SDp\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"stddevp\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-stddevp.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_medianDefaultAggFunc_passesSchemaGate() {
        // PR #6a (Codex 019e2695): median is now a valid registry
        // token; schema gate must let it through. The numeric-column
        // constraint is enforced at the controller sanitizeAggregations
        // layer rather than the JSON schema gate (cross-field rules
        // would be too heavy to express in the schema).
        String json = "{\"contractVersion\":1,\"key\":\"r-median\",\"version\":\"1.0\","
                + "\"title\":\"Med\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"median\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-median.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_garbageDefaultAggFunc_rejectedBySchemaGate() {
        // Codex 019e2695 iter-5 absorb: the negative schema test now
        // uses a permanently invalid token rather than `median`
        // (now valid in PR #6a) or roadmap tokens like `percentile`
        // (PR #6b) and `weightedavg` (PR-0.4) that will become valid
        // in upcoming PRs.
        String json = "{\"contractVersion\":1,\"key\":\"r-garbage\",\"version\":\"1.0\","
                + "\"title\":\"Garbage\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"garbage_xyz\"}]}";

        List<ContractViolation> violations = validator.validate(json, "r-garbage.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("defaultAggFunc"));
    }

    @Test
    void validate_percentileContWithDefaultAggParams_passesSchemaGate() {
        // PR #6b: percentilecont + defaultAggParams.percentile in [0,1]
        // is a valid schema configuration.
        String json = "{\"contractVersion\":1,\"key\":\"r-pct\",\"version\":\"1.0\","
                + "\"title\":\"Pct\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"percentilecont\","
                + "\"defaultAggParams\":{\"percentile\":0.9}}]}";

        List<ContractViolation> violations = validator.validate(json, "r-pct.json");

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_percentileContWithOutOfRangeParam_rejected() {
        // PR #6b: percentile outside [0,1] must be caught at the
        // schema gate (number max=1).
        String json = "{\"contractVersion\":1,\"key\":\"r-pct-bad\",\"version\":\"1.0\","
                + "\"title\":\"PctBad\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"percentilecont\","
                + "\"defaultAggParams\":{\"percentile\":1.5}}]}";

        List<ContractViolation> violations = validator.validate(json, "r-pct-bad.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("percentile"));
    }

    @Test
    void validate_defaultAggParamsUnknownProperty_rejected() {
        // additionalProperties=false guard: stray keys in defaultAggParams
        // must fail the schema gate so config typos surface early.
        String json = "{\"contractVersion\":1,\"key\":\"r-pct-extra\",\"version\":\"1.0\","
                + "\"title\":\"PctExtra\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"AMOUNT\",\"headerName\":\"Amount\","
                + "\"type\":\"number\",\"aggregatable\":true,"
                + "\"defaultAggFunc\":\"percentilecont\","
                + "\"defaultAggParams\":{\"percentile\":0.9,\"unknown\":\"x\"}}]}";

        List<ContractViolation> violations = validator.validate(json, "r-pct-extra.json");

        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId()));
    }

    @Test
    void validate_pivotableColumnFlag_passesSchemaGate() {
        // PR-0.4a (Codex 019e2695): the new per-column `pivotable` flag
        // is a plain boolean — the schema gate just needs to accept it
        // without breaking existing reports.
        String json = "{\"contractVersion\":1,\"key\":\"r-pivot\",\"version\":\"1.0\","
                + "\"title\":\"Pivot\",\"category\":\"Finans\","
                + "\"source\":\"TBL\",\"sourceSchema\":\"workcube_mikrolink_1\","
                + "\"schemaMode\":\"static\","
                + "\"tenantBoundary\":{\"mode\":\"schema\",\"scopeType\":\"tenant\","
                + "\"schemaResolver\":\"sourceSchemaLiteral\","
                + "\"schemaPattern\":\"workcube_mikrolink_{tenantId}\","
                + "\"reason\":\"Tenant-scoped via literal schema name suffix\"},"
                + "\"columns\":[{\"field\":\"ACTION_TYPE\",\"headerName\":\"Action\","
                + "\"type\":\"text\",\"groupable\":true,\"pivotable\":true}]}";

        List<ContractViolation> violations = validator.validate(json, "r-pivot.json");

        assertThat(violations).isEmpty();
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
