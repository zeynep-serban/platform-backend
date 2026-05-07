package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RC-006 — schemaMode=canonical reports cannot reference tenant fact tables.
 *
 * <p>Canonical (master/lookup) schemaMode reports use {@code workcube_mikrolink}
 * non-tenant-scoped schema; referencing tenant fact tables (ACCOUNT_CARD_ROWS,
 * INVOICES, vb.) violates separation between master data and tenant data.
 *
 * <p>Bu sub-PR (1a) hardcoded tenant fact table list ile heuristic; 1d'de
 * SchemaSnapshotLoader üzerinden sınıflandırma metadata-driven olur.
 */
public final class RC006NoneModeForbidsTenantFactTables implements ContractRule {

    private static final Set<String> KNOWN_TENANT_FACT_TABLES = Set.of(
            "ACCOUNT_CARD_ROWS",
            "ACCOUNT_CARD",
            "ACCOUNT_CARD_MONEY",
            "INVOICES",
            "INVOICE_ROWS",
            "PURCHASE_ORDERS",
            "SALES_ORDERS",
            "STOCK_MOVEMENT",
            "CONTRACTS"
    );

    @Override
    public String ruleId() {
        return "RC-006";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        // RC-006 applies to canonical (master/lookup) reports.
        if (!"canonical".equals(def.schemaMode())) {
            return List.of();
        }

        List<ContractViolation> violations = new ArrayList<>();

        // Direct source table check
        if (def.source() != null && KNOWN_TENANT_FACT_TABLES.contains(def.source().toUpperCase())) {
            violations.add(ContractViolation.fail(
                    ruleId(), def.key(), "source",
                    "schemaMode=canonical (master/lookup) cannot reference tenant fact table '"
                            + def.source() + "'"));
        }

        // sourceQuery scan for known tenant fact tables
        if (def.sourceQuery() != null) {
            String upper = def.sourceQuery().toUpperCase();
            for (String factTable : KNOWN_TENANT_FACT_TABLES) {
                if (upper.contains("." + factTable + "]")
                        || upper.contains("." + factTable + " ")
                        || upper.contains(" " + factTable + " ")) {
                    violations.add(ContractViolation.fail(
                            ruleId(), def.key(), "sourceQuery",
                            "schemaMode=canonical cannot reference tenant fact table '"
                                    + factTable + "' in sourceQuery"));
                    break; // one violation per report is enough
                }
            }
        }

        return violations;
    }
}
