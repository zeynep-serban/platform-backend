package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EndpointAuditEventDto(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID commandId,
        String eventType,
        String action,
        String performedBySubject,
        String correlationId,
        Map<String, Object> metadata,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        Instant occurredAt
) {
}
