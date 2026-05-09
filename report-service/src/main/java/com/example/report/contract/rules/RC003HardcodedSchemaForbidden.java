package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RC-003 — Hardcoded {@code workcube_mikrolink_<n>} or
 * {@code workcube_mikrolink_<yyyy>_<n>} schema literals forbidden.
 *
 * <p>Codex 019e0c99 iter-3 §D absorb: regex strengthened to a boundary-aware,
 * case-insensitive pattern that catches single-digit tenant hardcodes (e.g.
 * the {@code workcube_mikrolink_7} literal in 6 finance reports' sourceQuery
 * that bypassed the previous 4-digit-year-only regex). Master global schema
 * {@code workcube_mikrolink} (no trailing digits) is NOT matched.
 *
 * <p>Severity policy (Codex iter-3 §4):
 * <ul>
 *   <li><strong>{@code sourceQuery}</strong>: any numeric hardcode → FAIL.
 *       sourceQuery is the runtime SQL surface; tenant isolation is at risk
 *       if a tenant scope ever differs from the hardcoded tenant.</li>
 *   <li><strong>{@code sourceSchema}</strong>: existing static-mode-only
 *       behavior preserved (Tier 0 FAIL, Tier 1+ legacy WARN). Yearly mode
 *       sourceSchema cleanup is deferred to a separate registry migration
 *       PR (Codex iter-2 §4 — 21 yearly reports currently have numeric
 *       sourceSchema; broad FAIL would block them).</li>
 * </ul>
 */
public final class RC003HardcodedSchemaForbidden implements ContractRule {

    /**
     * Boundary-aware regex (Codex 019e0c99 iter-3 §3 prescription):
     * <ul>
     *   <li>{@code (?<![A-Za-z0-9_])} — left boundary so {@code my_workcube_mikrolink}
     *       false-positives don't match.</li>
     *   <li>{@code workcube_mikrolink_} — fixed prefix.</li>
     *   <li>{@code (\\d+)} — first digit group (year if 4-digit, else tenant).</li>
     *   <li>{@code (?:_(\\d+))?} — optional second digit group (tenant when
     *       year-prefixed).</li>
     *   <li>{@code (?![A-Za-z0-9_])} — right boundary; the digit-less
     *       master {@code workcube_mikrolink} is NOT matched (no trailing
     *       digits at all).</li>
     * </ul>
     */
    private static final Pattern HARDCODED_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])workcube_mikrolink_(\\d+)(?:_(\\d+))?(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);

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

        // sourceQuery scan — any numeric hardcode → FAIL
        // (Codex iter-3 §4: tenant isolation runtime surface).
        if (def.sourceQuery() != null) {
            scanSourceQuery(def.key(), def.sourceQuery(), violations);
        }
        // sourceSchema scan — only static-mode reports get the legacy
        // tier check; yearly sourceSchema cleanup is a separate PR.
        if ("static".equals(def.schemaMode()) && def.sourceSchema() != null) {
            scanSourceSchema(def.key(), def.sourceSchema(), violations);
        }

        return violations;
    }

    /**
     * sourceQuery scan: any match → FAIL. Each match is reported separately
     * so authors see all hardcodes (e.g. multi-line UNION sourceQuery may
     * reference several tenants).
     */
    private void scanSourceQuery(String reportKey, String text,
                                 List<ContractViolation> out) {
        Matcher m = HARDCODED_PATTERN.matcher(text);
        while (m.find()) {
            String literal = m.group();
            String detail;
            if (m.group(2) != null) {
                detail = "year=" + m.group(1) + ", tenantId=" + m.group(2);
            } else {
                detail = "tenantId=" + m.group(1);
            }
            out.add(ContractViolation.fail(ruleId(), reportKey, "sourceQuery",
                    "Hardcoded schema literal '" + literal + "' (" + detail + ") in "
                            + "sourceQuery — tenant isolation runtime surface; use "
                            + "{schema} (yearly transaction) or "
                            + "{tenantSetupProcessCatRelation} (per-tenant master "
                            + "lookup) placeholder instead"));
        }
    }

    /**
     * sourceSchema scan (static-mode only): preserve previous tier logic —
     * Tier 0 (current year + tenant hardcoded) FAIL; Tier 1+ legacy WARN.
     *
     * <p>Codex 019e0c99 iter-5 §3 absorb: single-tenant {@code workcube_mikrolink_<n>}
     * (no year prefix) is INTENTIONALLY skipped here. Existing static reports
     * {@code satis-ozet} and {@code stok-durum} carry {@code sourceSchema:
     * workcube_mikrolink_1}; broad-failing them in this PR would block
     * unrelated registry migration. Yearly/static sourceSchema cleanup is a
     * separate registry-migration PR. The sourceQuery scan above keeps the
     * runtime-tenant-isolation FAIL semantics this PR set out to fix.
     */
    private void scanSourceSchema(String reportKey, String text,
                                  List<ContractViolation> out) {
        Matcher m = HARDCODED_PATTERN.matcher(text);
        while (m.find()) {
            // Year-prefixed pattern only — single-tenant `_<n>` skipped (see javadoc).
            if (m.group(2) == null) {
                continue;
            }
            String literal = m.group();
            int year = Integer.parseInt(m.group(1));
            if (year >= currentYear) {
                out.add(ContractViolation.fail(ruleId(), reportKey, "sourceSchema",
                        "Hardcoded current-period schema '" + literal
                                + "' forbidden — use schemaMode=yearly + {schema} placeholder"));
            } else {
                out.add(ContractViolation.warn(ruleId(), reportKey, "sourceSchema",
                        "Hardcoded legacy schema '" + literal
                                + "' (year=" + year + " < " + currentYear + " current); "
                                + "migration to schemaMode=yearly recommended"));
            }
        }
    }
}
