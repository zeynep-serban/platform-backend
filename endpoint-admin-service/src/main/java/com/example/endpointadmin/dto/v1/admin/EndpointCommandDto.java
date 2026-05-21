package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EndpointCommandDto(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        CommandType type,
        String idempotencyKey,
        CommandStatus status,
        Map<String, Object> payload,
        Integer priority,
        Integer attemptCount,
        Integer maxAttempts,
        String lockedBy,
        Instant lockedUntil,
        Instant visibleAfterAt,
        Instant expiresAt,
        String issuedBySubject,
        Instant issuedAt,
        Instant deliveredAt,
        Instant ackedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        EndpointCommandResultDto result
) {
}
