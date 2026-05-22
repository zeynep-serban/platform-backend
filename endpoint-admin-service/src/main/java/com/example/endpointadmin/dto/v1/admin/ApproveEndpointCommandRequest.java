package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ApprovalDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * BE-017 — a second admin's dual-control decision on a destructive command.
 * {@code reason} is mandatory for a REJECT decision (validated in the service
 * layer); optional for APPROVE.
 */
public record ApproveEndpointCommandRequest(
        @NotNull
        ApprovalDecision decision,

        @Size(max = 512)
        String reason
) {
}
