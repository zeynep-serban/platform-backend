package com.example.report.contract;

import com.example.report.contract.report.ContractReport;
import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.rules.ContractRule;
import com.example.report.contract.rules.RC000SchemaModeEnumValid;
import com.example.report.contract.rules.RC001YearlyRequiresYearColumn;
import com.example.report.contract.rules.RC002YearlySourceQueryRequiresPlaceholder;
import com.example.report.contract.rules.RC003HardcodedSchemaForbidden;
import com.example.report.contract.rules.RC004RowFilterColumnAllowlisted;
import com.example.report.contract.rules.RC005SchemaModePlusRowFilterForbidden;
import com.example.report.contract.rules.RC006NoneModeForbidsTenantFactTables;
import com.example.report.contract.rules.RC007ColumnFieldExistsInSourceQuery;
import com.example.report.contract.rules.RC008SchemaResolverRegistered;
import com.example.report.contract.rules.RC009ActionScopeValid;
import com.example.report.contract.rules.RC010DestructiveActionRequiresPermissionAndConfirm;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 Program 1 — Contract validation orchestrator.
 *
 * <p>Spec §2.1 build-time gate disiplini:
 * <ul>
 *   <li><strong>NOT</strong> {@code @Component} / {@code @Configuration} —
 *       production runtime'da component scan'de aktive olmaz; CI'da
 *       {@code mvn test} {@code @ParameterizedTest} registry sweep'inde çağrılır.</li>
 *   <li>11 RC rules (RC-000..RC-010) wired in fixed order.</li>
 *   <li>{@link ContractRule#validate(ReportDefinition)} sequencing: each rule
 *       independent; rules don't see each other's output.</li>
 * </ul>
 *
 * <p>Usage (test classpath):
 * <pre>{@code
 * ContractValidator validator = ContractValidator.withDefaultRules();
 * List<ContractViolation> violations = validator.validate(reportDef);
 * }</pre>
 */
public final class ContractValidator {

    private final List<ContractRule> rules;

    public ContractValidator(List<ContractRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Default rule set: 11 RC rules (RC-000..RC-010) wired in spec order.
     */
    public static ContractValidator withDefaultRules() {
        return new ContractValidator(List.of(
                new RC000SchemaModeEnumValid(),
                new RC001YearlyRequiresYearColumn(),
                new RC002YearlySourceQueryRequiresPlaceholder(),
                new RC003HardcodedSchemaForbidden(),
                new RC004RowFilterColumnAllowlisted(),
                new RC005SchemaModePlusRowFilterForbidden(),
                new RC006NoneModeForbidsTenantFactTables(),
                new RC007ColumnFieldExistsInSourceQuery(),
                new RC008SchemaResolverRegistered(),
                new RC009ActionScopeValid(),
                new RC010DestructiveActionRequiresPermissionAndConfirm()
        ));
    }

    /**
     * Validate a single {@link ReportDefinition}.
     *
     * @param def report registry entry
     * @return all violations from all rules (preserves rule order; same rule's
     *         multiple violations preserved in list order)
     */
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        List<ContractViolation> all = new ArrayList<>();
        for (ContractRule rule : rules) {
            try {
                List<ContractViolation> ruleViolations = rule.validate(def);
                if (ruleViolations != null && !ruleViolations.isEmpty()) {
                    all.addAll(ruleViolations);
                }
            } catch (RuntimeException e) {
                // Codex iter-1 BLOCKING absorb: rule implementation crash =
                // validator infrastructure error → FAIL fail-closed (NOT WARN).
                // RC-007 "WARN escalation YASAK" prensibi heuristic uyarıları
                // için; rule crash'i ayrı kategori. WARN olsaydı bozuk FAIL
                // rule sessizce CI'dan geçerdi.
                all.add(ContractViolation.fail(
                        rule.ruleId(), def.key(), null,
                        "RULE_EXECUTION_ERROR: rule threw RuntimeException: " + e.getMessage()
                                + " — validator failed closed; fix rule implementation"));
            }
        }
        return all;
    }

    /**
     * Validate a batch of {@link ReportDefinition}s; aggregate into a
     * {@link ContractReport} for PR-feedback summary generation.
     */
    public ContractReport validateAll(List<ReportDefinition> definitions) {
        if (definitions == null) {
            return ContractReport.empty(0);
        }
        List<ContractViolation> all = new ArrayList<>();
        for (ReportDefinition def : definitions) {
            all.addAll(validate(def));
        }
        return new ContractReport(all, definitions.size());
    }

    public int ruleCount() {
        return rules.size();
    }
}
