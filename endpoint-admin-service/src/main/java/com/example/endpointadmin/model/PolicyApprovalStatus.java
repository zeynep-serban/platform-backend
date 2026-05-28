package com.example.endpointadmin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wave-12 PR-5 — lifecycle status of a policy-change approval request.
 *
 * <ul>
 *   <li>{@code PENDING} — proposed, no approver has interacted yet;</li>
 *   <li>{@code IN_REVIEW} — at least one reviewer requested changes; the
 *       request stays open for revision;</li>
 *   <li>{@code APPROVED} — terminal positive decision;</li>
 *   <li>{@code REJECTED} — terminal negative decision (reviewer rejection);</li>
 *   <li>{@code WITHDRAWN} — terminal: proposer retracted before any reviewer
 *       decision (mirrors the platform-web design-system status union);</li>
 *   <li>{@code EXPIRED} — deadline elapsed without a terminal decision.</li>
 * </ul>
 *
 * <p>Only {@code PENDING} and {@code IN_REVIEW} accept further decisions.
 *
 * <p>JSON wire format is lowercase snake_case ({@code "pending"},
 * {@code "in_review"}, …) so the platform-web design-system
 * {@code ApprovalRequestStatus} contract round-trips verbatim.
 */
public enum PolicyApprovalStatus {
    PENDING("pending"),
    IN_REVIEW("in_review"),
    APPROVED("approved"),
    REJECTED("rejected"),
    WITHDRAWN("withdrawn"),
    EXPIRED("expired");

    private final String wire;

    PolicyApprovalStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PolicyApprovalStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (PolicyApprovalStatus s : values()) {
            if (s.wire.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown PolicyApprovalStatus: " + value);
    }
}
