package com.example.endpointadmin.model;

/**
 * BE-017 — dual-control approval state of an {@link EndpointCommand}, tracked
 * orthogonally to {@link CommandStatus} (the agent delivery lifecycle).
 *
 * <ul>
 *   <li>{@code NOT_REQUIRED} — non-destructive command, no approval gate;</li>
 *   <li>{@code PENDING} — destructive command awaiting a second admin;</li>
 *   <li>{@code APPROVED} — a second admin approved; the command is now
 *       claimable by the agent;</li>
 *   <li>{@code REJECTED} — a second admin rejected; the command is cancelled.</li>
 * </ul>
 *
 * Only {@code NOT_REQUIRED} and {@code APPROVED} commands are agent-deliverable.
 */
public enum ApprovalStatus {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED
}
