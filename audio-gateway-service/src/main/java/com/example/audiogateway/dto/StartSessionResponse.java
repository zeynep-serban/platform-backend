package com.example.audiogateway.dto;

/**
 * Response for session start — sessionId Gateway-generated UUID, correlationId from header
 * (or fresh UUID if absent), tenantId/userId Gateway-derived from JWT (NOT echoed back raw —
 * hash or omit to avoid PII leak per ADR-0030).
 *
 * <p>ADR-0031 + Codex {@code 019e8c26} iter-2: paths canonical {@code /api/v1/audio-gateway}.
 * Stream/chunks URL'leri PR-gw-01D / PR-gw-01B slice'larında functional olur — bu surface
 * "planned contract" niteliğinde, A acceptance value değil. Status/finish URL functional.
 */
public record StartSessionResponse(
        String sessionId,
        String correlationId,
        String websocketUrl,
        String chunkUploadUrl,
        String statusUrl,
        String finishUrl,
        long sessionStartMs
) {
}
