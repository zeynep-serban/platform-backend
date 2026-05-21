package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EnrollmentStatus;

import java.time.Instant;
import java.util.UUID;

public record EndpointEnrollmentDto(
        UUID id,
        UUID tenantId,
        EnrollmentStatus status,
        String requestedBySubject,
        String note,
        UUID deviceId,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
}
