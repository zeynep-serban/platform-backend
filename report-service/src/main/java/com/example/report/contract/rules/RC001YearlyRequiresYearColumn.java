package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-001 — schemaMode=yearly requires non-empty yearColumn.
 *
 * <p>Yearly schema resolver tipik {@code workcube_mikrolink_{year}_{companyId}}
 * pattern'ini çözer; year resolution için {@code yearColumn} (örn. {@code RECORD_DATE},
 * {@code ACTION_DATE}) zorunlu — aksi halde year picker yok.
 */
public final class RC001YearlyRequiresYearColumn implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-001";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if ("yearly".equals(def.schemaMode())) {
            if (def.yearColumn() == null || def.yearColumn().isBlank()) {
                return List.of(ContractViolation.fail(
                        ruleId(), def.key(), "yearColumn",
                        "schemaMode=yearly requires non-empty yearColumn (year resolution source)"));
            }
        }
        return List.of();
    }
}
