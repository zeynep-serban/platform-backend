package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.ReportingAllowlist;
import com.example.report.contract.schema.TableRef;
import com.example.report.contract.schema.WorkcubeSqlTableRefScanner;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-011 — Workcube source table must be in {@link ReportingAllowlist#V1}.
 *
 * <p>Phase 2 Program 11.1 (Codex {@code 019e258f} iter-17 PARTIAL absorb):
 * every {@code "source": "..."} entry in a report definition must address
 * a table the platform officially exposes via the Workcube reporting
 * allowlist. Unknown source tables fail the build, preventing accidental
 * inclusion of tables that haven't been catalogued / had type mapping
 * resolved.
 *
 * <p><b>Scope (PR-1, extended by Adım 11.2a)</b>: this rule validates
 * both {@code ReportDefinition.source} and {@code sourceQuery} table
 * references against {@link ReportingAllowlist#V1}. Source field is
 * checked directly; sourceQuery is scanned by
 * {@link WorkcubeSqlTableRefScanner}. Unknown tables / unsupported
 * targets (table variables, temp tables, OPENQUERY) fail-closed.
 *
 * <p>Codex iter-20 absorb: V1 expanded 30 → 40 via sourceQuery inventory
 * sweep (added ACCOUNT_PLAN, COMPANY, CONSUMER, EMPLOYEES,
 * EMPLOYEES_DETAIL, EMPLOYEES_IDENTY, EMPLOYEES_PUANTAJ, EXPENSE_ITEMS,
 * MONEY_HISTORY, SETUP_DOCUMENT_TYPE).
 *
 * <p><b>Why a separate rule</b> (vs piggy-backing on {@code
 * SchemaTruthLookupPolicy.BUILD_DETERMINISTIC}): the policy enum encodes
 * tier-fallback strategy (Tier 1 vs 2 vs 3), not allowlist membership
 * — Codex iter-17 S3 explicit: "Policy enum'u semantik tier davranışı
 * için; allowlist üyeliği ayrı validator rule olmalı."
 */
public final class RC011WorkcubeSourceAllowlisted implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-011";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        List<ContractViolation> violations = new ArrayList<>();

        // Source field check (PR-1 baseline)
        if (def.source() != null && !def.source().isBlank()
                && !ReportingAllowlist.containsV1(def.source())) {
            violations.add(ContractViolation.fail(
                    ruleId(), def.key(), "source",
                    "Workcube source table '" + def.source()
                            + "' is not in ReportingAllowlist.V1 (pre-SEAL snapshot, "
                            + "40 tables). Add the table to ReportingAllowlist or "
                            + "validate via Faz 16.1 annex 2A SEAL before referencing."));
        }

        // sourceQuery scan (Adım 11.2a extension)
        if (def.sourceQuery() != null && !def.sourceQuery().isBlank()) {
            for (TableRef ref : WorkcubeSqlTableRefScanner.scan(def.sourceQuery())) {
                // Unsupported targets fail-closed regardless of allowlist
                if (ref.schemaKind() == TableRef.SchemaKind.UNKNOWN) {
                    violations.add(ContractViolation.fail(
                            ruleId(), def.key(), "sourceQuery",
                            "Unsupported table target in sourceQuery: '" + ref.raw()
                                    + "' at position " + ref.position()
                                    + " (table variables, temp tables, OPENQUERY, 3/4-part names "
                                    + "are not supported)."));
                    continue;
                }
                // Allowlist enforcement for tenant-bound + canonical schemas
                if (!ReportingAllowlist.containsV1(ref.table())) {
                    violations.add(ContractViolation.fail(
                            ruleId(), def.key(), "sourceQuery",
                            "Workcube table '" + ref.table() + "' (schema '"
                                    + ref.schema() + "') in sourceQuery at position "
                                    + ref.position() + " is not in ReportingAllowlist.V1."));
                }
            }
        }

        return violations;
    }
}
