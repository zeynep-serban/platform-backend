package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminAgentUpdateReleaseRevokeRequest(
        @NotBlank
        @Size(max = 512)
        String revocationReason
) {
}
