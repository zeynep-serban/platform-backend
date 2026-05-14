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
 * <p><b>Scope (Adım 11.2a)</b>:
 * <ul>
 *   <li>{@code ReportDefinition.source} validates direct table name against
 *       {@link ReportingAllowlist#V1}.</li>
 *   <li>{@code ReportDefinition.sourceQuery} validates template-level table
 *       refs that are explicit in SQL ({@code [{schema}].[TABLE]},
 *       {@code [workcube_mikrolink].[TABLE]}, two-part unbracketed).</li>
 *   <li>Unsupported / unqualified targets (UNKNOWN, UNQUALIFIED schemaKind)
 *       fail-closed; declare via {@code [{schema}].[TABLE]} or
 *       {@code [workcube_mikrolink].[TABLE]} instead.</li>
 *   <li>Rendered placeholder expansion (especially
 *       {@code {tenantSetupProcessCatRelation}}) is deferred to Adım 11.2b
 *       rendered-SQL enforcement.</li>
 * </ul>
 *
 * <p>Codex iter-20 absorb: V1 expanded 30 → 40 via sourceQuery inventory
 * sweep (added ACCOUNT_PLAN, COMPANY, CONSUMER, EMPLOYEES,
 * EMPLOYEES_DETAIL, EMPLOYEES_IDENTY, EMPLOYEES_PUANTAJ, EXPENSE_ITEMS,
 * MONEY_HISTORY, SETUP_DOCUMENT_TYPE).
 *
 * <p>Codex iter-21 absorb: UNQUALIFIED schemaKind fail-closed; sourceQuery
 * branch independent of source presence; violation messages use dynamic
 * V1 size so V2 update doesn't drift.
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
                            + ReportingAllowlist.V1.size() + " tables). Add the table "
                            + "to ReportingAllowlist or validate via Faz 16.1 annex 2A "
                            + "SEAL before referencing."));
        }

        // sourceQuery scan (Adım 11.2a extension; Codex iter-20/21 absorb)
        if (def.sourceQuery() != null && !def.sourceQuery().isBlank()) {
            for (TableRef ref : WorkcubeSqlTableRefScanner.scan(def.sourceQuery())) {
                // Codex iter-21 REVISE-1 #2: UNQUALIFIED fail-closed in sourceQuery
                // context. ReportDefinition.source carries unqualified names safely;
                // sourceQuery SQL surface must declare schema/placeholder explicitly.
                if (ref.schemaKind() == TableRef.SchemaKind.UNKNOWN
                        || ref.schemaKind() == TableRef.SchemaKind.UNQUALIFIED) {
                    violations.add(ContractViolation.fail(
                            ruleId(), def.key(), "sourceQuery",
                            "Unsupported or unqualified table target in sourceQuery: '"
                                    + ref.raw() + "' at position " + ref.position()
                                    + " (table variables, temp tables, OPENQUERY, "
                                    + "3/4-part names, and unqualified tables are not "
                                    + "supported — declare via [{schema}].[TABLE] or "
                                    + "[workcube_mikrolink].[TABLE])."));
                    continue;
                }
                // Allowlist enforcement for tenant-bound + canonical schemas
                if (!ReportingAllowlist.containsV1(ref.table())) {
                    violations.add(ContractViolation.fail(
                            ruleId(), def.key(), "sourceQuery",
                            "Workcube table '" + ref.table() + "' (schema '"
                                    + ref.schema() + "') in sourceQuery at position "
                                    + ref.position() + " is not in ReportingAllowlist.V1 ("
                                    + ReportingAllowlist.V1.size() + " tables pre-SEAL)."));
                }
            }
        }

        return violations;
    }
}
