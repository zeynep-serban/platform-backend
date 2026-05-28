package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wave-12 PR-5 — policy-change approval request, as surfaced to the
 * platform-web {@code ApprovalRequest} + {@code PolicyApprovalDomainExtras}
 * contract.
 *
 * <p>{@link #type()} is fixed to {@code policy_change} so the polymorphic
 * platform-web union picks the policy variant. {@code history} is
 * append-only and ordered oldest-first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyChangeApprovalDto(
        UUID id,
        String type,
        String title,
        String target,
        ApprovalActorDto proposer,
        String reason,
        List<String> evidenceRefs,
        Instant createdAt,
        Instant updatedAt,
        Instant deadline,
        PolicyApprovalStatus status,
        List<ApprovalActorDto> currentApprovers,
        List<DecisionRecordDto> history,
        PolicyChangeKind changeKind,
        PolicyRiskTier riskTier,
        Map<String, Object> before,
        Map<String, Object> after
) {
}
