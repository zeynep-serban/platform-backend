package com.example.permission.audit;

/**
 * Scope marker for audit read paths.
 *
 * <p>Each {@code /api/audit/events*} read endpoint (list, findById, export,
 * export-jobs, live stream) declares the scope it serves; the service layer
 * uses this to apply impersonation inclusion/exclusion rules consistently.
 *
 * <p>PR-D2: Generic AUDIT viewer must never see impersonation rows; the
 * dedicated IMPERSONATION_AUDIT viewer sees only impersonation rows. Each
 * scope is gated by a different {@code @RequireModule} annotation.
 *
 * <p>Codex peer review thread {@code 019e10bf} iter-2 AGREE WITH AMENDMENTS.
 */
public enum AuditReadScope {

    /**
     * Generic audit feed: {@code /api/audit/events}, {@code /export},
     * {@code /export-jobs}, {@code /live}. Impersonation rows
     * (IMPERSONATION_STARTED / STOPPED / BLOCKED / FAILED / REVOKED) are
     * unconditionally excluded. Gated by {@code @RequireModule("AUDIT", ...)}.
     */
    GENERIC_AUDIT,

    /**
     * Dedicated impersonation audit feed:
     * {@code /api/audit/events/impersonation}. Restricted to the 5 canonical
     * impersonation action codes. Gated by
     * {@code @RequireModule("IMPERSONATION_AUDIT", ...)}.
     */
    IMPERSONATION_AUDIT
}
