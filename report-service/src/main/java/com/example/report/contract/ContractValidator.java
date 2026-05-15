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
import com.example.report.contract.rules.RC011WorkcubeSourceAllowlisted;
import com.example.report.contract.rules.RC012AuthzReferenceCheck;
import com.example.report.contract.schema.TenantColumnAllowlist;
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
 *   <li>12 RC rules (RC-000..RC-011) wired in fixed order — RC-011 added
 *       2026-05-14 (Adım 11.1) for Workcube source-table allowlist gating.</li>
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
     * Default rule set with empty allowlist (1a backward compat). RC-004 with
     * an empty allowlist behaves as deny-all for COMPANY rowFilter columns —
     * production gate uses {@link #withDefaultRules(TenantColumnAllowlist)}
     * with the real allowlist; this overload exists for 1a unit fixtures that
     * pre-date allowlist injection and don't trip RC-004.
     */
    public static ContractValidator withDefaultRules() {
        return withDefaultRules(new TenantColumnAllowlist(java.util.Map.of()));
    }

    /**
     * Phase 2 Program 1d (Codex iter-4 §1d-AGREE absorb): default rule set
     * with injected tenant column allowlist for RC-004. Production gate uses
     * this overload via {@code ReportContractGate}; 1a tests retain
     * {@link #withDefaultRules()} for empty-allowlist behavior.
     *
     * @param allowlist tenant column allowlist (RC-004 input)
     */
    public static ContractValidator withDefaultRules(TenantColumnAllowlist allowlist) {
        return withDefaultRules(allowlist, (com.example.report.contract.schema.BuildTimeSchemaExistenceLookup) null);
    }

    /**
     * Phase 2 Program 2c iter-15 absorb (legacy 2-arg overload): yearly
     * coverage lookup only (no canonical). Production gate uses the 3-arg
     * overload {@link #withDefaultRules(TenantColumnAllowlist, com.example.report.contract.schema.BuildTimeSchemaExistenceLookup)}.
     */
    public static ContractValidator withDefaultRules(
            TenantColumnAllowlist allowlist,
            com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup yearlyOnly) {
        com.example.report.contract.schema.BuildTimeSchemaExistenceLookup unified =
                yearlyOnly == null
                        ? null
                        : new com.example.report.contract.schema.BuildTimeSchemaExistenceLookup(null, yearlyOnly);
        return withDefaultRules(allowlist, unified);
    }

    /**
     * Phase 2 Program 2c iter-18 absorb (Codex thread 019e0119): default rule
     * set with injected allowlist + unified existence lookup (canonical +
     * yearly). RC-004 routes existence probe via canonical snapshot first
     * (HR/static tables), then yearly fallback (CARI_ROWS, INVOICE_ROW, ...).
     *
     * @param allowlist       tenant column allowlist (RC-004 input)
     * @param existenceLookup unified existence lookup (canonical + yearly);
     *                        null → existence check skipped, 1d backward-compat
     */
    public static ContractValidator withDefaultRules(
            TenantColumnAllowlist allowlist,
            com.example.report.contract.schema.BuildTimeSchemaExistenceLookup existenceLookup) {
        return new ContractValidator(List.of(
                new RC000SchemaModeEnumValid(),
                new RC001YearlyRequiresYearColumn(),
                new RC002YearlySourceQueryRequiresPlaceholder(),
                new RC003HardcodedSchemaForbidden(),
                new RC004RowFilterColumnAllowlisted(allowlist, existenceLookup),
                new RC005SchemaModePlusRowFilterForbidden(),
                new RC006NoneModeForbidsTenantFactTables(),
                new RC007ColumnFieldExistsInSourceQuery(),
                new RC008SchemaResolverRegistered(),
                new RC009ActionScopeValid(),
                new RC010DestructiveActionRequiresPermissionAndConfirm(),
                new RC011WorkcubeSourceAllowlisted(),
                new RC012AuthzReferenceCheck()
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
