package com.example.endpointadmin.service.compliance;

/**
 * Stable reason vocabulary for BE-023 compliance evaluation.
 *
 * <p>Codes are intentionally machine-readable
 * lowercase identifiers (used by UI for i18n keys, by future BE-024
 * reporting layer for SLO queries, by audit metadata for cross-tenant
 * forensic searches). Codex 019e6bbf iter-3 final list — locked.
 *
 * <p>Categorisation: each reason carries a {@link Severity} that
 * declares how it affects the decision precedence ladder. The
 * evaluator uses this categorisation directly; UI callers can rely on
 * the severity classifier to render banner / chip / row-level UX
 * without coupling to the enum name.
 */
public enum ComplianceReason {

    // ─── BLOCK → NON_COMPLIANT ────────────────────────────────────
    MISSING_REQUIRED_APP("missing_required_app", Severity.NON_COMPLIANT),
    OUTDATED_REQUIRED_APP("outdated_required_app", Severity.NON_COMPLIANT),

    // ─── BLOCK → UNAUTHORIZED ─────────────────────────────────────
    FORBIDDEN_APP_INSTALLED("forbidden_app_installed", Severity.UNAUTHORIZED),
    // BE-025 (Faz 22.5): a tenant-scoped prohibited-software denylist rule
    // (NOT catalog-bound) matched the device's installed inventory by name
    // and/or publisher. Same UNAUTHORIZED severity bucket as
    // FORBIDDEN_APP_INSTALLED — the device decision collapses to
    // UNAUTHORIZED — but a distinct code so evidence / UI can tell a
    // catalog-FORBIDDEN hit from a denylist hit. Detection only; no
    // auto-uninstall.
    PROHIBITED_APP_INSTALLED("prohibited_app_installed", Severity.UNAUTHORIZED),

    // ─── UNKNOWN-driving ──────────────────────────────────────────
    INVENTORY_MISSING("inventory_missing", Severity.UNKNOWN),
    INVENTORY_UNSUPPORTED("inventory_unsupported", Severity.UNKNOWN),
    APPS_UNAVAILABLE("apps_unavailable", Severity.UNKNOWN),
    INVENTORY_STALE_HARD("inventory_stale_hard", Severity.UNKNOWN),
    INVENTORY_TRUNCATED("inventory_truncated", Severity.UNKNOWN),
    VERSION_COMPARE_UNSUPPORTED("version_compare_unsupported", Severity.UNKNOWN),
    DETECTION_RULE_UNSUPPORTED("detection_rule_unsupported", Severity.UNKNOWN),
    POLICY_CATALOG_ITEM_UNAVAILABLE("policy_catalog_item_unavailable", Severity.UNKNOWN),
    POLICY_EMPTY("policy_empty", Severity.UNKNOWN),
    WINGET_EGRESS_MISSING("winget_egress_missing", Severity.UNKNOWN),
    WINGET_EGRESS_UNSUPPORTED("winget_egress_unsupported", Severity.UNKNOWN),
    WINGET_EGRESS_SCHEMA_UNSUPPORTED("winget_egress_schema_unsupported", Severity.UNKNOWN),

    // ─── WARN-only (decoration; does not flip decision) ───────────
    INVENTORY_STALE_SOFT("inventory_stale_soft", Severity.WARN),
    WINGET_EGRESS_PARTIAL("winget_egress_partial", Severity.WARN),
    WINGET_SOURCE_LIST_WARNING("winget_source_list_warning", Severity.WARN),
    // BE-021 (Codex 019e6dfb iter-3 P0-4): emitted when the install
    // audit fallback selector found a SUCCEEDED+SATISFIED audit but the
    // subsequent inventory snapshot (collected past the install grace
    // window) no longer reports the package. The audit is invalidated
    // and the catalog item falls back to MISSING_REQUIRED_APP; this
    // warning surfaces the contradiction in the evidence block.
    INSTALL_AUDIT_CONTRADICTED_BY_INVENTORY("install_audit_contradicted_by_inventory", Severity.WARN);

    public enum Severity {
        WARN,
        UNKNOWN,
        NON_COMPLIANT,
        UNAUTHORIZED
    }

    private final String code;
    private final Severity severity;

    ComplianceReason(String code, Severity severity) {
        this.code = code;
        this.severity = severity;
    }

    public String code() {
        return code;
    }

    public Severity severity() {
        return severity;
    }
}
