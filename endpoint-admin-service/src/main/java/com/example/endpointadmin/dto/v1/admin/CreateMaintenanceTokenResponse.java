package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.MaintenanceAction;

import java.time.Instant;
import java.util.UUID;

public record CreateMaintenanceTokenResponse(
        UUID tokenId,
        String token,
        MaintenanceAction action,
        Instant expiresAt
) {
}
