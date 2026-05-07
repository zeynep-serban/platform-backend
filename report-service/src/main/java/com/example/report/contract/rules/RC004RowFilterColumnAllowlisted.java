package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-004 — rowFilter.scopeType=COMPANY column allowlist check.
 *
 * <p>Phase 2 Program 1d (Codex iter-4 §1d-AGREE absorb): allowlist match
 * doğrulanır. Schema truth existence cross-check (column gerçekten snapshot'ta
 * var mı) Phase 2 Program 2 follow-up'a kaydırıldı çünkü mevcut canonical
 * snapshot yearly-partitioned finance source tablolarını kapsamıyor.
 *
 * <p>Fail modes (1d):
 * <ul>
 *   <li>{@code COMPANY rowFilter requires resolvable source table for
 *       allowlist validation; sourceQuery alone is not sufficient}</li>
 *   <li>{@code Column 'X' not in tenant column allowlist for source 'Y'}</li>
 * </ul>
 *
 * <p>Backward-compat constructor (no allowlist) defaults to a deny-all
 * empty allowlist; in 1a stub mode this rule was no-op so existing
 * ContractValidatorTest fixtures kept passing without a full allowlist.
 */
public final class RC004RowFilterColumnAllowlisted implements ContractRule {

    private final TenantColumnAllowlist allowlist;

    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist) {
        this.allowlist = allowlist;
    }

    /**
     * No-arg constructor for backward compatibility (1a tests + factory).
     * Behaves like an empty allowlist — every legitimate COMPANY rowFilter
     * column will FAIL, so callers must inject the real allowlist for
     * production gate use.
     */
    public RC004RowFilterColumnAllowlisted() {
        this(new TenantColumnAllowlist(java.util.Map.of()));
    }

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

        // Codex iter-4 §1d-AGREE: sourceQuery+no source → tek FAIL, allowlist skip.
        // source table çözülemiyorsa allowlist + existence check anlamlı değil.
        if (def.source() == null || def.source().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "COMPANY rowFilter requires resolvable source table for "
                            + "allowlist validation; sourceQuery alone is not sufficient"));
        }

        List<ContractViolation> violations = new ArrayList<>();

        // Allowlist match check.
        if (!allowlist.allows(def.source(), rowFilter.column())) {
            violations.add(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "Column '" + rowFilter.column() + "' not in tenant column "
                            + "allowlist for source '" + def.source() + "' "
                            + "(scopeType=COMPANY rowFilter must use a tenant boundary column; "
                            + "review allowlist or correct scopeType)"));
        }

        // Phase 2 Program 2 follow-up: schema truth existence cross-check
        // (snapshot'ta tablo + kolon gerçekten var mı). 1d'de DEFER edildi
        // çünkü canonical snapshot yearly finance source'larını kapsamıyor.

        return violations;
    }
}
