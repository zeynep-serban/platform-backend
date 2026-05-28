package com.example.endpointadmin.model;

/**
 * Device-level compliance verdict — BE-023 (Faz 22.5).
 *
 * <p>Decision precedence (Codex 019e6bbf iter-3 AGREE):
 * <ol>
 *   <li>{@link #UNAUTHORIZED} — at least one {@code FORBIDDEN} catalog
 *       item is evidenced as installed. Preserved even under
 *       {@code INVENTORY_STALE_HARD} so the finding is not silently
 *       masked by stale telemetry.</li>
 *   <li>{@link #UNKNOWN} — telemetry is insufficient (inventory missing,
 *       hard-stale, truncated, apps unavailable, version comparator
 *       fail-closed, policy catalog gap, empty policy, egress missing /
 *       unsupported / schema-bad) AND no positive {@code FORBIDDEN}
 *       evidence.</li>
 *   <li>{@link #NON_COMPLIANT} — at least one {@code REQUIRED} catalog
 *       item is missing or installed at a version that fails the
 *       catalog version policy, telemetry healthy, no
 *       {@code FORBIDDEN} evidence.</li>
 *   <li>{@link #COMPLIANT} — every {@code REQUIRED} satisfied, no
 *       {@code FORBIDDEN} evidence, telemetry healthy.</li>
 * </ol>
 *
 * <p>v1 scope explicit limitation: {@code UNAPPROVED_APP_DETECTED}
 * (generic "installed software not in approved catalog") is deferred
 * to BE-024 along with a machine-readable {@code audited_scope_matcher}
 * DSL. v1 {@code UNAUTHORIZED} is produced *only* by
 * {@code FORBIDDEN_APP_INSTALLED}.
 */
public enum ComplianceDecision {
    COMPLIANT,
    NON_COMPLIANT,
    UNAUTHORIZED,
    UNKNOWN
}
