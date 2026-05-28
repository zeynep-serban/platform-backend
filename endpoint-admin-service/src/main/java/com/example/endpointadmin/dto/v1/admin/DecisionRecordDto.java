package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wave-12 PR-5 — append-only history entry for a policy-change approval.
 * The {@code action} property discriminates between the five concrete
 * shapes; mirrors the platform-web design-system {@code DecisionRecord}
 * union ({@code approve} / {@code reject} / {@code request_changes} /
 * {@code delegate} / {@code attest}).
 *
 * <p>Common fields ({@code id}, {@code actor}, {@code actorRole},
 * {@code reason}, {@code evidenceRefs}, {@code previousStatus},
 * {@code newStatus}, {@code timestamp}) match {@code DecisionRecordBase}
 * on the front-end. {@code delegate} adds {@code delegateTo}; {@code
 * attest} adds nested {@code attestation}.
 *
 * <p>The {@code action} discriminator wire value is lowercase snake_case
 * (e.g. {@code "request_changes"}); status enum values flow through their
 * own {@code @JsonValue} mapping.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "action")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DecisionRecordDto.Approve.class, name = "approve"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Reject.class, name = "reject"),
        @JsonSubTypes.Type(value = DecisionRecordDto.RequestChanges.class,
                name = "request_changes"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Delegate.class, name = "delegate"),
        @JsonSubTypes.Type(value = DecisionRecordDto.Attest.class, name = "attest")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface DecisionRecordDto {

    UUID id();

    ApprovalActorDto actor();

    String actorRole();

    String reason();

    List<String> evidenceRefs();

    PolicyApprovalStatus previousStatus();

    PolicyApprovalStatus newStatus();

    Instant timestamp();

    record Approve(UUID id, ApprovalActorDto actor, String actorRole,
                   String reason, List<String> evidenceRefs,
                   PolicyApprovalStatus previousStatus,
                   PolicyApprovalStatus newStatus, Instant timestamp)
            implements DecisionRecordDto {
    }

    record Reject(UUID id, ApprovalActorDto actor, String actorRole,
                  String reason, List<String> evidenceRefs,
                  PolicyApprovalStatus previousStatus,
                  PolicyApprovalStatus newStatus, Instant timestamp)
            implements DecisionRecordDto {
    }

    record RequestChanges(UUID id, ApprovalActorDto actor, String actorRole,
                          String reason, List<String> evidenceRefs,
                          PolicyApprovalStatus previousStatus,
                          PolicyApprovalStatus newStatus, Instant timestamp)
            implements DecisionRecordDto {
    }

    record Delegate(UUID id, ApprovalActorDto actor, String actorRole,
                    String reason, List<String> evidenceRefs,
                    PolicyApprovalStatus previousStatus,
                    PolicyApprovalStatus newStatus, Instant timestamp,
                    ApprovalActorDto delegateTo) implements DecisionRecordDto {
    }

    record Attest(UUID id, ApprovalActorDto actor, String actorRole,
                  String reason, List<String> evidenceRefs,
                  PolicyApprovalStatus previousStatus,
                  PolicyApprovalStatus newStatus, Instant timestamp,
                  DecisionAttestationDto attestation) implements DecisionRecordDto {
    }
}
