package com.example.report.contract.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 2 Program 1 — Contract validation report aggregate.
 *
 * <p>Spec §3.2 PR sticky comment summary basement. Aggregates per-rule
 * violations + severity histogram + report inventory.
 */
public record ContractReport(
        List<ContractViolation> violations,
        int reportCount
) {

    public ContractReport {
        if (violations == null) {
            violations = List.of();
        }
        violations = List.copyOf(violations);
    }

    public List<ContractViolation> failures() {
        return violations.stream()
                .filter(v -> v.severity() == ContractViolation.Severity.FAIL)
                .toList();
    }

    public List<ContractViolation> warnings() {
        return violations.stream()
                .filter(v -> v.severity() == ContractViolation.Severity.WARN)
                .toList();
    }

    public Map<String, List<ContractViolation>> failuresByReport() {
        return failures().stream()
                .collect(Collectors.groupingBy(ContractViolation::reportKey));
    }

    public boolean hasFailures() {
        return !failures().isEmpty();
    }

    public static ContractReport empty(int reportCount) {
        return new ContractReport(new ArrayList<>(), reportCount);
    }
}
