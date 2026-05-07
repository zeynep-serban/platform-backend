package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-004 — rowFilter.scopeType=COMPANY column allowlist check.
 *
 * <p>Tenant column allowlist + schema-service existence check Phase-2-Program-1d
 * SchemaSnapshotLoader integration ile tamamlanır; bu sub-PR (1a) sadece
 * rowFilter shape validation yapar (column non-blank).
 *
 * <p>Allowlist + existence wiring 1d'de eklenir; 1a stub.
 */
public final class RC004RowFilterColumnAllowlisted implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-004";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        AccessConfig access = def.access();
        if (access == null || access.rowFilter() == null) {
            return List.of();
        }
        AccessConfig.RowFilter rowFilter = access.rowFilter();
        if (!"COMPANY".equals(rowFilter.scopeType())) {
            return List.of();
        }
        if (rowFilter.column() == null || rowFilter.column().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "rowFilter.scopeType=COMPANY requires non-blank column"));
        }
        // Phase-2-Program-1d: ColumnTypeRegistry.exists(ctx, schema, table, column)
        // call wired here for full DB-level allowlist check.
        return List.of();
    }
}
