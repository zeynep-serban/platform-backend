package com.example.report.contract.report;

/**
 * Phase 2 Program 1 — Contract validation violation record.
 *
 * <p>Spec §2.3 RC-000..RC-010 rule output unit. Severity {@link Severity#FAIL}
 * CI-blocking; {@link Severity#WARN} summary-only (CI doesn't fail).
 *
 * @param ruleId      RC-000..RC-010 (RC-011 frontend separate)
 * @param severity    FAIL | WARN
 * @param reportKey   Report registry key
 * @param field       Affected field path (örn. "schemaMode", "rowFilter.scopeType",
 *                    "actions[0].scope") — null if global to the report
 * @param message     Human-readable explanation
 */
public record ContractViolation(
        String ruleId,
        Severity severity,
        String reportKey,
        String field,
        String message
) {

    public enum Severity {
        FAIL,
        WARN
    }

    public ContractViolation {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be blank");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (reportKey == null || reportKey.isBlank()) {
            throw new IllegalArgumentException("reportKey must not be blank");
        }
    }

    public static ContractViolation fail(String ruleId, String reportKey, String field, String message) {
        return new ContractViolation(ruleId, Severity.FAIL, reportKey, field, message);
    }

    public static ContractViolation warn(String ruleId, String reportKey, String field, String message) {
        return new ContractViolation(ruleId, Severity.WARN, reportKey, field, message);
    }
}
