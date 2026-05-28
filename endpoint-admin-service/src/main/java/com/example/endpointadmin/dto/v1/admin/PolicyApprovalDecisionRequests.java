package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wave-12 PR-5 — request bodies for the five {@code policy-approvals}
 * decision endpoints. Grouped in one file because each shape is a small
 * record specific to its endpoint.
 *
 * <p>The {@code actor} field carries the actor identity from the
 * platform-web {@code ApprovalActor} contract. <b>The {@code actor.id}
 * is cross-checked against the authenticated subject server-side</b> —
 * a mismatch is rejected as {@code 400 actor_mismatch} so a body-supplied
 * identity cannot be used to spoof the 4-eyes governance guard.
 *
 * <p>{@code evidenceRefs} (optional, max 32 refs) lets reviewers attach
 * supporting links / ticket ids to a decision; mirrors the
 * design-system {@code DecisionRecordBase.evidenceRefs} field.
 */
public final class PolicyApprovalDecisionRequests {

    private PolicyApprovalDecisionRequests() {
    }

    public record ApproveRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @Size(max = 2048) String reason,
            @Size(max = 32) List<@Size(max = 512) String> evidenceRefs
    ) {
    }

    public record RejectRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @NotBlank @Size(max = 2048) String reason,
            @Size(max = 32) List<@Size(max = 512) String> evidenceRefs
    ) {
    }

    public record RequestChangesRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @NotBlank @Size(max = 2048) String reason,
            @Size(max = 32) List<@Size(max = 512) String> evidenceRefs
    ) {
    }

    public record DelegateRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @Valid @NotNull ApprovalActorDto delegateTo,
            @Size(max = 2048) String reason,
            @Size(max = 32) List<@Size(max = 512) String> evidenceRefs
    ) {
    }

    public record AttestRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @Valid @NotNull DecisionAttestationDto attestation,
            @Size(max = 32) List<@Size(max = 512) String> evidenceRefs
    ) {
    }
}
