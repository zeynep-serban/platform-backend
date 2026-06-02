package com.example.audiogateway.dto;

/**
 * Response for session start — sessionId Gateway-generated UUID, correlationId from header
 * (or fresh UUID if absent), tenantId/userId Gateway-derived from JWT (NOT echoed back raw —
 * hash or omit to avoid PII leak per ADR-0030).
 */
public record StartSessionResponse(
        String sessionId,
        String correlationId,
        String websocketUrl,
        String chunkUploadUrl,
        long sessionStartMs
) {
}
