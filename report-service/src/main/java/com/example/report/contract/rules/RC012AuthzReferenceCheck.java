package com.example.report.contract.rules;

import com.example.report.contract.registry.OpenFgaModelAuthzReferenceRegistry;
import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-012 — {@code access.reportGroup} authz reference registry'de tanımlı mı?
 *
 * <p>R16 PR-C (Codex 019e27f5 PARTIAL absorb Option 2): authz contract drift
 * source-side guard. {@code ReportDefinition.access.reportGroup} ("FINANCE_REPORTS",
 * "HR_REPORTS", ...) için canonical OpenFGA model'de {@code type report_group}
 * tanımlı olmalı. Yoksa WARN üretir (PR-C kapsamı: WARN-first).
 *
 * <p>{@code authz-reference-debt.yaml} ile explicit deferral mekanizması;
 * {@code warn_until} tarihi geçince WARN → FAIL'e döner (sonraki iter).
 *
 * <p>R15 regression guard: PR-B (canonical model'e {@code type report_group}
 * ekleme) merge edilmezse, RC-012 her PR'da WARN log üretir — silent drift
 * imkansızlaşır.
 *
 * <h2>Codex 019e27f5 PR-C kararı</h2>
 * Bu rule source contract gate; runtime OpenFGA API çağrısı YAPMAZ. Type-level
 * varlık kontrolü yapar; instance-level tuple varlığı PR-B-2'de runtime
 * data plane guard'ında.
 *
 * @see RC008SchemaResolverRegistered  similar pattern (Set + check)
 * @see OpenFgaModelAuthzReferenceRegistry
 * @see <a href="../../../resources/contract/authz-reference-debt.yaml">authz-reference-debt.yaml</a>
 */
public final class RC012AuthzReferenceCheck implements ContractRule {

    private final OpenFgaModelAuthzReferenceRegistry registry;

    public RC012AuthzReferenceCheck() {
        this(new OpenFgaModelAuthzReferenceRegistry());
    }

    public RC012AuthzReferenceCheck(OpenFgaModelAuthzReferenceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String ruleId() {
        return "RC-012";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        List<ContractViolation> violations = new ArrayList<>();

        if (def.access() == null || def.access().reportGroup() == null
                || def.access().reportGroup().isBlank()) {
            // reportGroup yok → bu rule uygulanmaz (deny-default zaten yok)
            return violations;
        }

        String reportGroup = def.access().reportGroup();

        if (!registry.isReportGroupTypeRegistered()) {
            // Type registry'de yok → WARN-first; tüm reportGroup-yazılı raporlar
            // canonical authz contract'tan kopuk. PR-B merge edilirse fix.
            violations.add(ContractViolation.warn(
                    ruleId(), def.key(), "access.reportGroup",
                    "reportGroup='" + reportGroup
                            + "' specified but canonical OpenFGA model is missing"
                            + " 'type report_group' definition. Merge PR-B"
                            + " (#196) or migrate the report to a registered"
                            + " authz reference. See ADR-0017 + R16 close-out"
                            + " discipline."));
            return violations;
        }

        // Codex 019e27f5 PR-C REVISE P1 absorb: actual reportGroup registry
        // check (type-level kontrolü ek olarak). Permission catalog
        // (PermissionDataInitializer) source'da `reports.<GROUP>` key var mı?
        java.util.Set<String> knownGroups = registry.knownReportGroups();
        if (!knownGroups.isEmpty() && !knownGroups.contains(reportGroup)) {
            violations.add(ContractViolation.warn(
                    ruleId(), def.key(), "access.reportGroup",
                    "reportGroup='" + reportGroup + "' permission catalog'da"
                            + " tanımlı değil (PermissionDataInitializer source"
                            + " parse). Known groups: " + knownGroups
                            + ". Yeni reportGroup eklenirken permission catalog +"
                            + " OpenFGA tuple seed senkronize olmalı."
                            + " See ADR-0017 + authz-reference-debt.yaml."));
        }

        return violations;
    }
}
