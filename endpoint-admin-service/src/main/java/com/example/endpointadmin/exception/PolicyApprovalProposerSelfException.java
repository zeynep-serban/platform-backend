package com.example.endpointadmin.exception;

/**
 * Wave-12 PR-5 — 4-eyes governance violation: the proposer of a
 * policy-change approval request tried to approve their own request.
 * Mapped to {@code 403 Forbidden} with error code {@code proposer_self}
 * by {@code GlobalExceptionHandler}.
 */
public class PolicyApprovalProposerSelfException extends RuntimeException {

    public PolicyApprovalProposerSelfException(String message) {
        super(message);
    }
}
