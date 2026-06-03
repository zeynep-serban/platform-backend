package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;

/**
 * In-memory session metadata — emitted by {@link AudioSessionRegistry}.
 *
 * <p>PR-gw-01A scope: persistence iddiası YOK (Codex {@code 019e8c26} iter-2 AGREE).
 * Durability sonraki slice'larda Redis Streams (PR-gw-01C) ile gelir. Restart sonrası
 * mevcut session'lar kaybolur — A acceptance dışı.
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
        String finishIdempotencyKey,
        long finishedAtMs,
        long updatedAtMs
) {

    public SessionRecord withState(final SessionState newState, final long now) {
        return new SessionRecord(
                sessionId, tenantId, userId, meetingId, deviceId, language,
                audioFormat, sampleRateHz, channels,
                startIdempotencyKey, sessionStartMs,
                newState, finishIdempotencyKey, finishedAtMs, now);
    }

    public SessionRecord withFinish(final String finishKey, final long finishedAt) {
        return new SessionRecord(
                sessionId, tenantId, userId, meetingId, deviceId, language,
                audioFormat, sampleRateHz, channels,
                startIdempotencyKey, sessionStartMs,
                SessionState.FINISHED, finishKey, finishedAt, finishedAt);
    }
}
