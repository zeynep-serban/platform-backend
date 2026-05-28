package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wave-12 PR-5 — input for {@code POST /api/v1/admin/policy-approvals}.
 * Mirrors the platform-web {@code ProposePolicyChangeInput} contract.
 *
 * <p>{@code currentApprovers} is the initial reviewer list; subsequent
 * delegate decisions rewrite it atomically. {@code evidenceRefs},
 * {@code deadline}, {@code before}, {@code after} are optional.
 */
public record ProposePolicyChangeRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 255) String target,
        @Valid @NotNull ApprovalActorDto proposer,
        @NotBlank @Size(max = 2048) String reason,
        @Size(max = 32) List<@Size(max = 512) String> evidenceRefs,
        @Valid @Size(min = 1, max = 16) List<@NotNull ApprovalActorDto> currentApprovers,
        Instant deadline,
        @NotNull PolicyChangeKind changeKind,
        @NotNull PolicyRiskTier riskTier,
        Map<String, Object> before,
        Map<String, Object> after
) {
}
