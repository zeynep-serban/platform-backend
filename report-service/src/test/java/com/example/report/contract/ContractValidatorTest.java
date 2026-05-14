package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.rules.ContractRule;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 1a — ContractValidator orchestrator + 11 RC rule tests.
 *
 * <p>Spec §5.1 unit tests for build-time validator. Each rule has dedicated
 * test; orchestrator wires all 11.
 */
class ContractValidatorTest {

    private final ContractValidator validator = ContractValidator.withDefaultRules();

    @Test
    void ruleCount_isExactly11() {
        assertThat(validator.ruleCount()).isEqualTo(11);
    }

    @Test
    void RC000_failsForUnknownSchemaMode() {
        // Codex iter-2 §1 absorb: standard schemaMode → ENUM_VIOLATION
        ReportDefinition def = buildDef("test", "yearly", null);
        ReportDefinition standard = new ReportDefinition(
                def.key(), def.version(), def.title(), def.description(), def.category(),
                def.source(), def.sourceSchema(),
                "standard", // RC-000 violation
                def.yearColumn(), def.sourceQuery(), def.columns(),
                def.defaultSort(), def.defaultSortDirection(), def.access());

        List<ContractViolation> violations = validator.validate(standard);

        assertThat(violations).anyMatch(v -> "RC-000".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.FAIL
                && v.message().contains("ENUM_VIOLATION"));
    }

    @Test
    void RC001_failsWhenYearlyMissesYearColumn() {
        ReportDefinition def = buildDef("test", "yearly", "ACTION_DATE");
        // null yearColumn
        ReportDefinition broken = new ReportDefinition(
                def.key(), def.version(), def.title(), def.description(), def.category(),
                def.source(), def.sourceSchema(), "yearly",
                null, // missing yearColumn
                def.sourceQuery(), def.columns(),
                def.defaultSort(), def.defaultSortDirection(), def.access());

        List<ContractViolation> violations = validator.validate(broken);

        assertThat(violations).anyMatch(v -> "RC-001".equals(v.ruleId()));
    }

    @Test
    void RC002_failsWhenYearlySourceQueryMissesPlaceholder() {
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                null, "workcube_mikrolink", "yearly", "ACTION_DATE",
                "SELECT id FROM hardcoded.table", // no {schema}
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-002".equals(v.ruleId())
                && v.message().contains("{schema}"));
    }

    @Test
    void RC003_failsForCurrentYearHardcodedSchema() {
        int currentYear = java.time.Year.now().getValue();
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                null, "workcube_mikrolink", "static", null,
                "SELECT id FROM [workcube_mikrolink_" + currentYear + "_35].[ACCOUNT_CARD_ROWS]",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-003".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void RC003_failsForLegacyHardcodedSchemaInSourceQuery() {
        // Codex 019e0c99 iter-3 §4 absorb (intentional rule change):
        // sourceQuery is the runtime SQL surface — any numeric hardcode
        // (current OR legacy year) → FAIL, because tenant isolation is at
        // risk if a scope ever differs from the hardcoded tenant. The
        // previous test name "warns" reflected pre-iter-3 sourceSchema-only
        // semantics; the rule now applies FAIL to sourceQuery year-agnostic.
        // sourceSchema scan still tier-aware (current=FAIL, legacy=WARN) for
        // static-mode reports, exercised by RC003_warnsForLegacyHardcodedSourceSchema.
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                null, "workcube_mikrolink", "static", null,
                "SELECT id FROM [workcube_mikrolink_2020_35].[ACCOUNT_CARD_ROWS]",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-003".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void RC005_failsForYearlySchemaPlusCompanyRowFilter() {
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", "yearly", "ACTION_DATE",
                "SELECT * FROM [{schema}].[ACCOUNT_CARD_ROWS]",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null,
                        new AccessConfig.RowFilter("ACC_COMPANY_ID", "COMPANY", null)));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-005".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void RC006_failsForCanonicalReportReferencingFactTable() {
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink", "canonical", null,
                null,
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-006".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void RC007_warnsWhenColumnFieldNotInSourceQuery() {
        ReportDefinition def = new ReportDefinition(
                "test", "1", "Test", "test", "test",
                null, "workcube_mikrolink_2026_35", "yearly", "ACTION_DATE",
                "SELECT amount FROM [{schema}].[tbl]",
                List.of(
                        new ColumnDefinition("amount", "Tutar", "number", 50, false),
                        new ColumnDefinition("ghost_field", "Ghost", "text", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        List<ContractViolation> violations = validator.validate(def);

        assertThat(violations).anyMatch(v -> "RC-007".equals(v.ruleId())
                && v.severity() == ContractViolation.Severity.WARN
                && v.field().contains("ghost_field"));
    }

    @Test
    void RC008_passesForRegisteredYearlyResolver() {
        ReportDefinition def = buildDef("test", "yearly", "ACTION_DATE");

        List<ContractViolation> violations = validator.validate(def);

        // RC-008 should not flag yearly mode (workcube-year-company is registered)
        assertThat(violations).noneMatch(v -> "RC-008".equals(v.ruleId()));
    }

    @Test
    void validate_ruleRuntimeException_becomesFailViolation() {
        // Codex iter-1 BLOCKING absorb: rule crash → FAIL fail-closed.
        ContractRule broken = new ContractRule() {
            @Override public String ruleId() { return "RC-999"; }
            @Override public List<ContractViolation> validate(ReportDefinition def) {
                throw new RuntimeException("boom");
            }
        };
        ContractValidator validatorWithBroken = new ContractValidator(List.of(broken));

        List<ContractViolation> violations = validatorWithBroken.validate(
                buildDef("test", "yearly", "ACTION_DATE"));

        assertThat(violations).anyMatch(v ->
                "RC-999".equals(v.ruleId())
                        && v.severity() == ContractViolation.Severity.FAIL
                        && v.message().contains("RULE_EXECUTION_ERROR"));
    }

    @Test
    void validateAll_aggregatesAcrossDefinitions() {
        ReportDefinition good = buildDef("good", "yearly", "ACTION_DATE");
        ReportDefinition bad = new ReportDefinition(
                "bad", "1", "Bad", "test", "test",
                "ACCOUNT_CARD_ROWS", // source non-null
                "workcube_mikrolink",
                "standard", // RC-000 violation
                null, null,
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        var report = validator.validateAll(List.of(good, bad));

        assertThat(report.reportCount()).isEqualTo(2);
        assertThat(report.failures()).isNotEmpty();
        assertThat(report.failuresByReport()).containsKey("bad");
        assertThat(report.failuresByReport()).doesNotContainKey("good");
    }

    @Test
    void validate_validYearlyReport_passesAllRules() {
        ReportDefinition def = buildDef("clean", "yearly", "ACTION_DATE");

        List<ContractViolation> violations = validator.validate(def);

        // No FAIL violations
        assertThat(violations.stream()
                .filter(v -> v.severity() == ContractViolation.Severity.FAIL)
                .toList()).isEmpty();
    }

    private ReportDefinition buildDef(String key, String schemaMode, String yearColumn) {
        return new ReportDefinition(
                key, "1", "Test " + key, "test", "test",
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35",
                schemaMode, yearColumn,
                "SELECT amount FROM [{schema}].[ACCOUNT_CARD_ROWS]",
                List.of(new ColumnDefinition("amount", "Tutar", "number", 50, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));
    }
}
