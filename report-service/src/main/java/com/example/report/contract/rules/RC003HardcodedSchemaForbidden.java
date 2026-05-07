package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RC-003 — Hardcoded `workcube_mikrolink_YYYY_ID` + static schemaMode.
 *
 * <p>Tier 0 (current period — current year) FAIL; Tier 1+ legacy WARN
 * (Codex iter-3 absorb). Matches `workcube_mikrolink_<4digits>_<digits>`
 * pattern in sourceQuery and sourceSchema.
 */
public final class RC003HardcodedSchemaForbidden implements ContractRule {

    private static final Pattern HARDCODED_PATTERN =
            Pattern.compile("workcube_mikrolink_(\\d{4})_\\d+");

    private final int currentYear;

    public RC003HardcodedSchemaForbidden(int currentYear) {
        this.currentYear = currentYear;
    }

    public RC003HardcodedSchemaForbidden() {
        this(java.time.Year.now().getValue());
    }

    @Override
    public String ruleId() {
        return "RC-003";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        List<ContractViolation> violations = new ArrayList<>();

        // sourceQuery scan
        if (def.sourceQuery() != null) {
            checkText(def.key(), "sourceQuery", def.sourceQuery(), violations);
        }
        // sourceSchema scan (only flag if static-mode + hardcoded)
        if ("static".equals(def.schemaMode()) && def.sourceSchema() != null) {
            checkText(def.key(), "sourceSchema", def.sourceSchema(), violations);
        }

        return violations;
    }

    private void checkText(String reportKey, String field, String text,
                            List<ContractViolation> out) {
        Matcher m = HARDCODED_PATTERN.matcher(text);
        while (m.find()) {
            int year = Integer.parseInt(m.group(1));
            if (year >= currentYear) {
                // Tier 0 — current period FAIL
                out.add(ContractViolation.fail(ruleId(), reportKey, field,
                        "Hardcoded current-period schema '" + m.group()
                                + "' forbidden — use schemaMode=yearly + {schema} placeholder"));
            } else {
                // Tier 1+ — legacy WARN
                out.add(ContractViolation.warn(ruleId(), reportKey, field,
                        "Hardcoded legacy schema '" + m.group()
                                + "' (year=" + year + " < " + currentYear + " current); "
                                + "migration to schemaMode=yearly recommended"));
            }
        }
    }
}
