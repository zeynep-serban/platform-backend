package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-009 — actions[].scope must be `grid` | `row` | `selection`.
 *
 * <p>Phase 2 Program 3 (Action Menu Standard) frontend three-slot rendering;
 * scope value backend contract enforced at build-time.
 *
 * <p>NOTE: ReportDefinition record currently doesn't expose actions[]
 * (registry json'da var, ama record record'a parse edilmemiş henüz).
 * 1a stub — 1c migration sırasında actions field ReportDefinition'a eklenir,
 * sonra bu rule wire'lanır. Şimdilik no-op.
 */
public final class RC009ActionScopeValid implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-009";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        // 1a stub: actions field henüz ReportDefinition'da yok.
        // 1c'de actions parse + bu rule wire edilir.
        return List.of();
    }
}
