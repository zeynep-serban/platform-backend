package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-005 — tenantBoundary.mode=schema + non-empty rowFilter forbidden.
 *
 * <p>Boundary clarity: schema-level isolation already covers tenant scope;
 * row-level filter on top creates dual-enforcement ambiguity. Reports with
 * {@code mode=schema} should NOT have rowFilter set.
 *
 * <p>Phase 2 Program 1 introduces explicit {@code tenantBoundary} field;
 * 1a here uses {@link AccessConfig#rowFilter()} presence as proxy until
 * 1c migrates registry shape.
 */
public final class RC005SchemaModePlusRowFilterForbidden implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-005";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        // Schema-mode reports (tipik yearly/current) + rowFilter combination
        // = boundary clarity violation.
        boolean isSchemaModeReport = "yearly".equals(def.schemaMode())
                || "current".equals(def.schemaMode());
        if (!isSchemaModeReport) {
            return List.of();
        }

        AccessConfig access = def.access();
        if (access != null && access.rowFilter() != null) {
            AccessConfig.RowFilter rf = access.rowFilter();
            if ("COMPANY".equals(rf.scopeType())) {
                // mode=schema (yearly/current) already enforces tenant
                // isolation via {schema} resolver; row filter redundant.
                return List.of(ContractViolation.fail(
                        ruleId(), def.key(), "rowFilter",
                        "schemaMode=" + def.schemaMode()
                                + " (schema-level tenant isolation) "
                                + "+ rowFilter.scopeType=COMPANY is forbidden — "
                                + "boundary clarity (Phase 2 Program 2 §3.2)"));
            }
        }
        return List.of();
    }
}
