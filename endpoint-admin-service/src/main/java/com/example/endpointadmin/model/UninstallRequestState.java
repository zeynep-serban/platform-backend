package com.example.endpointadmin.model;

/**
 * AG-028 Phase 1 — lifecycle states for {@link EndpointUninstallRequest}.
 *
 * <p>State machine:
 * <pre>
 *   PENDING_APPROVAL → APPROVED → QUEUED → CLAIMED → RUNNING → TERMINAL
 *   PENDING_APPROVAL                                          → TERMINAL  (reject path)
 * </pre>
 *
 * <p>{@link #TERMINAL} is the sink for both success and failure terminal
 * states; the per-result detail lives in the immutable {@link EndpointUninstallAudit}
 * row with its {@code result_status} + {@code verification} taxonomy.
 *
 * <p>V32 DB CHECK enforces {@code state IN ('PENDING_APPROVAL','APPROVED',
 * 'QUEUED','CLAIMED','RUNNING','TERMINAL')}.
 */
public enum UninstallRequestState {
    PENDING_APPROVAL,
    APPROVED,
    QUEUED,
    CLAIMED,
    RUNNING,
    TERMINAL
}
