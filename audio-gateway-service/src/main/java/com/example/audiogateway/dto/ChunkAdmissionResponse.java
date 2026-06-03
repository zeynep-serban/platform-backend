package com.example.audiogateway.dto;

/**
 * Chunk admission response — POST /api/v1/audio-gateway/sessions/{sessionId}/chunks.
 *
 * <p>PR-gw-01B-core scope (Codex {@code 019e8d78} iter-2 AGREE): bu response gerçek
 * admission/state transition sonucu döner; placeholder/dummy 200 YASAK. Replay
 * (same Idempotency-Key + same effective request + same payload SHA-256) durumunda
 * {@code replayed=true} ile aynı response.
 *
 * <p>{@code payloadSha256} internal-only — response body'ye TAM hash KOYMA (Codex iter-2:
 * payload hash hassas veri fingerprint'i sayılabilir; response/audit prefix veya
 * internal-only field).
 */
public record ChunkAdmissionResponse(
        String sessionId,
        String correlationId,
        long chunkSeq,
        long chunkCount,
        long receivedAtMs,
        boolean replayed
) {
}
