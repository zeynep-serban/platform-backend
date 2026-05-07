package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Set;

/**
 * RC-000 — schemaMode enum validity (yearly/current/canonical/static).
 *
 * <p>Codex iter-2 §1 absorb (PR #91): RC-001 ile çakışma önlemek için ayrı
 * rule. Mevcut registry'deki {@code schemaMode=standard} (örn. fin-butce-
 * gerceklesen.json) FAIL ile yakalanır; Phase-2-Program-1c migration commit
 * gerekli.
 */
public final class RC000SchemaModeEnumValid implements ContractRule {

    private static final Set<String> VALID = Set.of("yearly", "current", "canonical", "static");

    @Override
    public String ruleId() {
        return "RC-000";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def.schemaMode() == null || def.schemaMode().isBlank()) {
            // RC-001 yearly check needs schemaMode; we'll FAIL here to surface enum gap.
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "schemaMode",
                    "schemaMode is null or blank; must be one of " + VALID));
        }
        if (!VALID.contains(def.schemaMode())) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "schemaMode",
                    "ENUM_VIOLATION: schemaMode='" + def.schemaMode()
                            + "' not in " + VALID
                            + " (Phase-2-Program-1c migration required for legacy values like 'standard')"));
        }
        return List.of();
    }
}
