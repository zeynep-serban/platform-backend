package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.MaintenanceAction;

import java.time.Instant;
import java.util.UUID;

public record ConsumeMaintenanceTokenResponse(
        MaintenanceAction action,
        UUID deviceId,
        Instant consumedAt
) {
}
