package com.example.endpointadmin.event;

import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.CommandType;

import java.util.UUID;

/**
 * Generic, type-agnostic domain event published by the shared dual-control
 * approve surface ({@code EndpointAdminCommandService.approveCommand}) after a
 * second admin's decision is persisted. It carries no display-policy specifics —
 * any command-type domain that needs to react to its own command's approval
 * subscribes with a synchronous in-transaction {@code @EventListener} and
 * filters by {@link #commandType()}.
 *
 * <p>#508 (Codex 019ea911 RED-fix): {@code DisplayPolicyApprovalListener}
 * consumes this to promote the backing revision to the current desired-state row
 * ONLY on APPROVE of a {@code SET_DISPLAY_POLICY} command — so a REJECTED
 * proposal never becomes current truth. Published for every decision; the
 * listener decides what (if anything) to do.
 */
public record CommandApprovalDecidedEvent(
        UUID commandId,
        CommandType commandType,
        ApprovalDecision decision,
        UUID tenantId,
        UUID deviceId,
        String decidedBySubject) {
}
