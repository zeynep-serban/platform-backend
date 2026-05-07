package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-010 — Destructive actions require non-null permission AND confirmation.
 *
 * <p>Phase 2 Program 3 spec §3.3: {@code destructive=true} actions cannot
 * have {@code permission=null} (REPORT_VIEW inherit), and must declare
 * {@code confirmation} payload (modal text).
 *
 * <p>1a stub — 1c'de actions field ReportDefinition'a eklenince wire'lanır.
 */
public final class RC010DestructiveActionRequiresPermissionAndConfirm implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-010";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        // 1a stub: actions[] henüz ReportDefinition'da yok.
        // 1c'de actions parse + bu rule wire edilir.
        return List.of();
    }
}
