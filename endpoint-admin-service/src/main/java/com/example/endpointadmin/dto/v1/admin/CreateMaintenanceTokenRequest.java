package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.MaintenanceAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMaintenanceTokenRequest(
        @NotNull
        MaintenanceAction action,

        @NotBlank
        @Size(max = 512)
        String reason,

        @NotNull
        @Min(1)
        @Max(10080)
        Integer expiresInMinutes
) {
}
