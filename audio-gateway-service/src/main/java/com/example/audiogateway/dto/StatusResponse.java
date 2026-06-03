package com.example.audiogateway.dto;

/**
 * Session status snapshot — GET /api/v1/audio-gateway/sessions/{sessionId}/status.
 *
 * <p>PR-gw-01A scope: {@code chunkCount} / {@code lastChunkSeq} / {@code durationMs} are
 * always 0 in this slice (no chunk admission yet — REST chunks PR-gw-01B, Redis Streams
 * PR-gw-01C, WebSocket PR-gw-01D). Lifecycle state ({@code STARTED} / {@code FINISHING}
 * / {@code FINISHED}) is functional.
 *
 * <p>Codex {@code 019e8c26} iter-2 AGREE — A acceptance: lifecycle state deterministik;
 * chunk counter sonraki slice'da gerçek değer verir.
 */
public record StatusResponse(
        String sessionId,
        String correlationId,
        String state,
        int chunkCount,
        long lastChunkSeq,
        long durationMs,
        long sessionStartMs,
        long updatedAtMs
) {
}
