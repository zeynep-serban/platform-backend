package com.example.endpointadmin.model;

/**
 * Device-level compliance verdict — BE-023 (Faz 22.5).
 *
 * <p>Decision precedence (Codex 019e6bbf iter-3 AGREE; the precedence
 * ladder itself — UNAUTHORIZED &gt; UNKNOWN &gt; NON_COMPLIANT &gt;
 * COMPLIANT — is the locked invariant, NOT the specific reason that
 * drives a given tier):
 * <ol>
 *   <li>{@link #UNAUTHORIZED} — at least one explicit-deny finding is
 *       evidenced as installed: a {@code FORBIDDEN} catalog item
 *       ({@code FORBIDDEN_APP_INSTALLED}) <b>or</b> (BE-025) a match
 *       against a tenant-scoped prohibited-software denylist rule
 *       ({@code PROHIBITED_APP_INSTALLED}). Preserved even under
 *       {@code INVENTORY_STALE_HARD} so the finding is not silently
 *       masked by stale telemetry.</li>
 *   <li>{@link #UNKNOWN} — telemetry is insufficient (inventory missing,
 *       hard-stale, truncated, apps unavailable, version comparator
 *       fail-closed, policy catalog gap, empty policy, egress missing /
 *       unsupported / schema-bad) AND no explicit-deny evidence.</li>
 *   <li>{@link #NON_COMPLIANT} — at least one {@code REQUIRED} catalog
 *       item is missing or installed at a version that fails the
 *       catalog version policy, telemetry healthy, no explicit-deny
 *       evidence.</li>
 *   <li>{@link #COMPLIANT} — every {@code REQUIRED} satisfied, no
 *       explicit-deny evidence, telemetry healthy.</li>
 * </ol>
 *
 * <p>v1 scope explicit limitation: {@code UNAPPROVED_APP_DETECTED}
 * (generic "installed software not in approved catalog") remains
 * deferred along with a machine-readable {@code audited_scope_matcher}
 * DSL. v1 {@code UNAUTHORIZED} is produced by the explicit-deny reasons
 * {@code FORBIDDEN_APP_INSTALLED} (BE-023) and
 * {@code PROHIBITED_APP_INSTALLED} (BE-025) — both
 * {@code Severity.UNAUTHORIZED}.
 */
public enum ComplianceDecision {
    COMPLIANT,
    NON_COMPLIANT,
    UNAUTHORIZED,
    UNKNOWN
}
