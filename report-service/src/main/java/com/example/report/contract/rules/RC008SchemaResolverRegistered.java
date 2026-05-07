package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Set;

/**
 * RC-008 — schemaResolver value must be in registered list.
 *
 * <p>Phase 2 Program 1 spec §2.2 introduces explicit {@code tenantBoundary
 * .schemaResolver} field; 1a here uses derived value from {@code schemaMode}
 * for backward compat. Registered resolvers:
 * <ul>
 *   <li>workcube-year-company (yearly)</li>
 *   <li>workcube-current-company (current — NEW in Phase 2 Program 2)</li>
 *   <li>none (canonical / static)</li>
 * </ul>
 *
 * <p>1c'de {@code tenantBoundary.schemaResolver} explicit field okunur;
 * 1a sadece schemaMode mapping integrity check.
 */
public final class RC008SchemaResolverRegistered implements ContractRule {

    private static final Set<String> REGISTERED_RESOLVERS = Set.of(
            "workcube-year-company",
            "workcube-current-company",
            "none"
    );

    @Override
    public String ruleId() {
        return "RC-008";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        // Derive expected resolver from schemaMode (1a backward-compat).
        // 1c'de def.tenantBoundary().schemaResolver() explicit okunur.
        String expectedResolver = switch (def.schemaMode() != null ? def.schemaMode() : "") {
            case "yearly" -> "workcube-year-company";
            case "current" -> "workcube-current-company";
            case "canonical", "static" -> "none";
            default -> null;
        };

        if (expectedResolver == null) {
            // RC-000 zaten enum violation flag'lar; double-fire önle.
            return List.of();
        }

        if (!REGISTERED_RESOLVERS.contains(expectedResolver)) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "schemaResolver",
                    "Resolver '" + expectedResolver + "' (derived from schemaMode='"
                            + def.schemaMode() + "') not in registered list "
                            + REGISTERED_RESOLVERS));
        }
        return List.of();
    }
}
