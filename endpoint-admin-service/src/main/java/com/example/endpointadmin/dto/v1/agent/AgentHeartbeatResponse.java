package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

public record AgentHeartbeatResponse(
        boolean accepted,
        UUID deviceId,
        DeviceStatus status,
        Instant serverTime
) {
}
