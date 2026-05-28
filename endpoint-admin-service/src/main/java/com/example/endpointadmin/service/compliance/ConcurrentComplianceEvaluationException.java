package com.example.endpointadmin.service.compliance;

/**
 * Thrown when an admin-initiated {@code POST /compliance/evaluate}
 * cannot acquire the per-(tenant, device) advisory lock because
 * another evaluation is already in flight. The controller maps this
 * to HTTP 409 + {@code Retry-After: 5}. Event-driven re-evaluations
 * (AFTER_COMMIT inventory ingest) silently skip — the next inventory
 * commit will fire another evaluation.
 *
 * <p>BE-023, Codex 019e6bbf iter-3 AGREE.
 */
public class ConcurrentComplianceEvaluationException extends RuntimeException {

    public ConcurrentComplianceEvaluationException(String message) {
        super(message);
    }
}
