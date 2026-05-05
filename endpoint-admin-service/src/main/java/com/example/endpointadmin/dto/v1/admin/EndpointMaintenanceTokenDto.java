package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.MaintenanceTokenStatus;

import java.time.Instant;
import java.util.UUID;

public record EndpointMaintenanceTokenDto(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        MaintenanceAction action,
        MaintenanceTokenStatus status,
        String reason,
        String issuedBySubject,
        Instant expiresAt,
        Instant consumedAt,
        String consumedByAgentVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
