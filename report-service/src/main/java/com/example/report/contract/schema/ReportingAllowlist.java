package com.example.report.contract.schema;

import java.util.Set;

/**
 * Phase 2 Program 11.1 — Workcube reporting source-table allowlist (V1
 * pre-SEAL snapshot, plan §7 Adım 11.1).
 *
 * <p>Codex thread {@code 019e258f} iter-17 PARTIAL absorb: backend ships an
 * in-repo hardcoded snapshot of the 40-table allowlist documented in GitOps
 * {@code docs/migration/mssql-inventory.md} (annex 2A). Drift between the
 * runtime list and the inventory markdown is caught by an ADR-0011 DD-1
 * governance gate on the GitOps side — backend CI does not read the sibling
 * repo at build time (Codex iter-17 S2: "backend CI'nin sibling
 * platform-k8s-gitops dosyasını okumaya bağımlı kalmasını önermem").
 *
 * <p><b>Why "V1"</b>: Faz 16.1 annex 2A SEAL (reconciliation of 44 cataloged
 * vs ~31 confirmed entries) is pending operator action. Until SEAL, this V1
 * snapshot is the named pre-SEAL version. {@code V2} will land once SEAL
 * delivers the canonical 40-table set; the named-version pattern keeps
 * migration steps explicit instead of in-place edits to V1.
 *
 * <h2>Composition</h2>
 * <ul>
 *   <li><b>23 canonical match tables</b>: documented in
 *       {@code docs/adr/0012-SS-schema-service-admin-operations.md} §2.2;
 *       all currently surface as {@code "source": "..."} in {@code
 *       report-service/src/main/resources/reports/*.json}.</li>
 *   <li><b>Parametric yearly partitions</b>: handled at SQL-build time via
 *       {@code workcube_mikrolink_<year>_<companyId>} schema substitution
 *       (not table-level — the underlying tables are the same canonical
 *       set above, just addressed in per-year schemas).</li>
 *   <li><b>Master / lookup tables</b>: {@code SETUP_PROCESS_CAT} +
 *       additional canonical references surfaced by the existing 32 report
 *       JSONs (avoids regression in {@link
 *       com.example.report.contract.rules.RC011WorkcubeSourceAllowlisted}).</li>
 * </ul>
 *
 * <h2>Why immutable + uppercase</h2>
 * SQL identifier comparisons are case-insensitive in MSSQL, but the
 * canonical inventory uses uppercase. Callers must normalize to uppercase
 * before consulting {@link #V1}. Returning an unmodifiable {@link Set}
 * avoids accidental runtime mutation in long-lived ApplicationContexts.
 */
public final class ReportingAllowlist {

    private ReportingAllowlist() {
    }

    /**
     * Pre-SEAL V1 allowlist — 23 ADR-0012-SS canonical + extras surfaced
     * in the current 32 report definitions. {@code V2} succeeds this set
     * after Faz 16.1 annex 2A SEAL.
     */
    public static final Set<String> V1 = Set.of(
            // ADR-0012-SS §2.2 23 canonical
            "INVOICE",
            "INVOICE_ROW",
            "CARI_ROWS",
            "CARI_ACTIONS",
            "BANK_ACTIONS",
            "CASH_ACTIONS",
            "CHEQUE",
            "COMPANY_REMAINDER",
            "ORDERS",
            "ORDER_ROW",
            "OUR_COMPANY",
            "BRANCH",
            "DEPARTMENT",
            "PRO_PROJECTS",
            "STOCK_FIS",
            "STOCK_FIS_ROW",
            "ACCOUNT_CARD",
            "ACCOUNT_CARD_ROWS",
            "ACCOUNT_CARD_MONEY",
            "EXPENSE_ITEM_PLANS",
            "SETUP_PROCESS_CAT",
            "EMPLOYEE_POSITIONS",
            "EMPLOYEES_SALARY",
            // Existing report JSON sources (regression-safety; surfaced in
            // src/main/resources/reports/*.json)
            "BUDGET_PLAN_ROW",
            "EMPLOYEES_IN_OUT",
            "EMPLOYEES_PUANTAJ_ROWS",
            "EMPLOYEES_SALARY_HISTORY",
            "EMPLOYEE_DAILY_IN_OUT",
            "OFFTIME",
            "TRAINING_CLASS_ATTENDER"
    );

    /**
     * Returns whether the given table name is in the V1 allowlist
     * (case-insensitive).
     */
    public static boolean containsV1(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        return V1.contains(tableName.toUpperCase(java.util.Locale.ROOT));
    }
}
