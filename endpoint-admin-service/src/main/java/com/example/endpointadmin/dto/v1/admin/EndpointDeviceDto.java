package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.OsType;

import java.time.Instant;
import java.util.UUID;

public record EndpointDeviceDto(
        UUID id,
        UUID tenantId,
        String hostname,
        String displayName,
        OsType osType,
        String osVersion,
        String agentVersion,
        String machineFingerprint,
        String domainName,
        DeviceStatus status,
        Instant lastSeenAt,
        Instant enrolledAt,
        Instant createdAt,
        Instant updatedAt
) {
}
