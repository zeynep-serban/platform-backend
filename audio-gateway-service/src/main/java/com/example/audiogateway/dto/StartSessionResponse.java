package com.example.audiogateway.dto;

/**
 * Response for session start — sessionId Gateway-generated UUID, correlationId from header
 * or fresh UUID, tenantId/userId Gateway-derived from JWT (NOT echoed back raw per
 * ADR-0030 PII guard).
 */
public record StartSessionResponse(
        String sessionId,
        String correlationId,
        String websocketUrl,
        String chunkUploadUrl,
        long sessionStartMs
) {
}
