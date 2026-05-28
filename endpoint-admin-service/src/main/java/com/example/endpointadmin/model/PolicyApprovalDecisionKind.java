package com.example.endpointadmin.model;

/**
 * Wave-12 PR-5 — discriminator for a {@code DecisionRecord} on a
 * {@link PolicyChangeApproval}. Mirrors the platform-web design-system
 * approval contract.
 */
public enum PolicyApprovalDecisionKind {
    APPROVE,
    REJECT,
    REQUEST_CHANGES,
    DELEGATE,
    ATTEST
}
