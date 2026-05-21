package com.example.endpointadmin.dto.v1.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsumeMaintenanceTokenRequest(
        @NotBlank
        @Size(max = 256)
        String maintenanceToken,

        @NotBlank
        @Size(max = 128)
        String agentVersion
) {
}
