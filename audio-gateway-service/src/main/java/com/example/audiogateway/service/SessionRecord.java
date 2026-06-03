package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;

/**
 * In-memory session metadata — emitted by {@link AudioSessionRegistry}.
 *
 * <p>PR-gw-01A scope: persistence iddiası YOK (Codex {@code 019e8c26} iter-2 AGREE).
 * Durability sonraki slice'larda Redis Streams (PR-gw-01C) ile gelir. Restart sonrası
 * mevcut session'lar kaybolur — A acceptance dışı.
 *
 * <p>PR-gw-01B-core absorb (Codex {@code 019e8d78} iter-2 AGREE): chunk admission state
 * fields eklendi — {@code lastAcceptedChunkSeq} (init -1, ilk admitted chunk için 0),
 * {@code chunkCount}, {@code lastChunkAtMs}, {@code lastChunkIdempotencyKey},
 * {@code lastChunkPayloadSha256} (idempotent replay için).
 *
 * <p>{@code finishIdempotencyKey} ve {@code finishedAtMs} finish öncesi {@code null} / 0.
 */
public record SessionRecord(
        String sessionId,
        Long tenantId,
        Long userId,
        String meetingId,
        String deviceId,
        String language,
        AudioFormat audioFormat,
        int sampleRateHz,
        int channels,
        String startIdempotencyKey,
        long sessionStartMs,
        SessionState state,
        long lastAcceptedChunkSeq,
        long chunkCount,
        long lastChunkAtMs,
        String lastChunkIdempotencyKey,
        String lastChunkPayloadSha256,
        String finishIdempotencyKey,
        long finishedAtMs,
        long updatedAtMs
) {

    public SessionRecord withState(final SessionState newState, final long now) {
        return new SessionRecord(
                sessionId, tenantId, userId, meetingId, deviceId, language,
                audioFormat, sampleRateHz, channels,
                startIdempotencyKey, sessionStartMs,
                newState,
                lastAcceptedChunkSeq, chunkCount, lastChunkAtMs,
                lastChunkIdempotencyKey, lastChunkPayloadSha256,
                finishIdempotencyKey, finishedAtMs, now);
    }

    public SessionRecord withAcceptedChunk(final long chunkSeq, final long chunkAtMs,
                                            final String idempotencyKey, final String payloadSha256) {
        return new SessionRecord(
                sessionId, tenantId, userId, meetingId, deviceId, language,
                audioFormat, sampleRateHz, channels,
                startIdempotencyKey, sessionStartMs,
                SessionState.STREAMING,
                chunkSeq, chunkCount + 1, chunkAtMs,
                idempotencyKey, payloadSha256,
                finishIdempotencyKey, finishedAtMs, chunkAtMs);
    }

    public SessionRecord withFinish(final String finishKey, final long finishedAt) {
        return new SessionRecord(
                sessionId, tenantId, userId, meetingId, deviceId, language,
                audioFormat, sampleRateHz, channels,
                startIdempotencyKey, sessionStartMs,
                SessionState.FINISHED,
                lastAcceptedChunkSeq, chunkCount, lastChunkAtMs,
                lastChunkIdempotencyKey, lastChunkPayloadSha256,
                finishKey, finishedAt, finishedAt);
    }
}
