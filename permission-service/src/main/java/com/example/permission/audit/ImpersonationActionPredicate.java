package com.example.permission.audit;

import java.util.Set;

/**
 * Centralized predicate for impersonation audit action classification.
 *
 * <p><b>Why this class exists (PR-D2):</b> the previous PR-D approach used
 * {@code containsIgnoreCase("IMPERSONATION", ...)} on the action filter,
 * which would also match {@code NON_IMPERSONATION_*} or
 * {@code SOMETHING_IMPERSONATION_BAR} action codes — a leak: any AUDIT viewer
 * could craft an action string containing the substring "IMPERSONATION" and
 * still hit the dedicated endpoint. Likewise the inverse leak: when scoping
 * the generic {@code /api/audit/events} feed to "exclude impersonation" using
 * substring matching, audit codes that *contain* the literal "IMPERSONATION"
 * (e.g. {@code NON_IMPERSONATION_*}) were also being excluded — losing
 * legitimate generic audit rows.
 *
 * <p>This predicate uses an explicit allowlist of the 5 canonical event types
 * declared in {@link ImpersonationAuditEventTypes}. All audit read paths
 * (generic list, dedicated impersonation list, export, export-jobs, live SSE)
 * MUST consult this class — never substring matching on the raw action.
 *
 * <p>Codex peer review thread {@code 019e10bf} iter-2 AGREE WITH AMENDMENTS.
 */
public final class ImpersonationActionPredicate {

    /**
     * Authoritative set of impersonation audit action codes. Mirrors
     * {@link ImpersonationAuditEventTypes} 1:1. Adding a new impersonation
     * event type requires extending this set and the catalog migration
     * (drift guard test asserts equivalence).
     */
    private static final Set<String> ALLOWED = Set.of(
            ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
            ImpersonationAuditEventTypes.IMPERSONATION_STOPPED,
            ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED,
            ImpersonationAuditEventTypes.IMPERSONATION_FAILED,
            ImpersonationAuditEventTypes.IMPERSONATION_REVOKED
    );

    /** Alias accepted on {@code /api/audit/events/impersonation?filter[action]=}. */
    public static final String ALIAS_ALL = "IMPERSONATION";

    private ImpersonationActionPredicate() {
        // utility
    }

    /**
     * Returns true iff the given action code is one of the 5 canonical
     * impersonation event types (exact, case-sensitive match).
     *
     * <p>Null-safe: a {@code null} action returns false (treat as
     * non-impersonation; this is the safe default for both inclusion and
     * exclusion paths — null-action rows are never impersonation).
     */
    public static boolean isImpersonationAction(String action) {
        if (action == null) {
            return false;
        }
        return ALLOWED.contains(action);
    }

    /**
     * Validates a {@code filter[action]} value submitted to the dedicated
     * {@code /api/audit/events/impersonation} endpoint.
     *
     * <p>Allowed values (else 400 Bad Request):
     * <ul>
     *   <li>{@code null} — caller did not narrow; service returns all 5 types</li>
     *   <li>{@code "IMPERSONATION"} — explicit alias for "all 5"</li>
     *   <li>One of the 5 canonical codes — narrows to that single type</li>
     * </ul>
     *
     * <p>Anything else (substrings, lowercase variants, fabricated codes
     * like {@code "FOO_IMPERSONATION_BAR"}) is rejected. The previous
     * "silently rewrite to IMPERSONATION" behavior is gone — clients now
     * get a clear 400 when they send garbage.
     */
    public static boolean isAllowedImpersonationFilter(String filterValue) {
        if (filterValue == null) {
            return true;
        }
        if (ALIAS_ALL.equals(filterValue)) {
            return true;
        }
        return ALLOWED.contains(filterValue);
    }

    /** Immutable view of the authoritative action set (for tests + drift guards). */
    public static Set<String> allActions() {
        return ALLOWED;
    }
}
