package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-002 — schemaMode=yearly + custom sourceQuery requires {schema} placeholder.
 *
 * <p>YearlySchemaResolver UNION ALL across {@code workcube_mikrolink_{year}_{companyId}}
 * branches; each branch's sourceQuery wraps with {@code FROM (sourceQuery) AS _src}.
 * Year-specific schema injection için {@code [{schema}]} placeholder zorunlu.
 */
public final class RC002YearlySourceQueryRequiresPlaceholder implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-002";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (!"yearly".equals(def.schemaMode())) {
            return List.of();
        }
        String src = def.sourceQuery();
        if (src == null || src.isBlank()) {
            return List.of();
        }
        if (!src.contains("{schema}")) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "sourceQuery",
                    "schemaMode=yearly + custom sourceQuery requires {schema} placeholder for "
                            + "yearly UNION ALL injection (e.g. FROM [{schema}].[ACCOUNT_CARD_ROWS])"));
        }
        return List.of();
    }
}
