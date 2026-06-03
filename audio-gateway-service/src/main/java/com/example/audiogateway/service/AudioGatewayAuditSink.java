package com.example.audiogateway.service;

/**
 * Audio Gateway audit sink — per-event emission point.
 *
 * <p>Codex {@code 019e8df2} iter-2 AGREE PR-gw-01B3 scope: tek event başlangıç
 * ({@link AuditEvent.ChunkAdmissionRejected}). SessionLifecycle/AuthFailure/
 * ChunkForwardedToPlatformAi B3 DEĞİL (ChunkForwardedToPlatformAi PR-gw-01C; auth audit
 * ayrı security/audit boundary).
 *
 * <p><b>Emission contract (Codex iter-2):</b> response yazılmadan ÖNCE sync emit;
 * {@code safeEmit(...)} pattern controller'da exception'ı primary response'tan ayırır
 * ({@code try { sink.emit(event); } catch (Exception ignored) {} } — Throwable catch YASAK).
 *
 * <p><b>PII boundary (ADR-0030 + Codex iter-2):</b> Audit event Idempotency-Key payload'a
 * YAZILMASIN (PII transitif sızıntı). Sadece: sessionId, tenantId, userId, chunkSeq,
 * httpStatus, rejectionCode, retryAfterSeconds (nullable), correlationId, timestamp.
 */
public interface AudioGatewayAuditSink {

    /** Emit a single audit event. Thread-safety: implementation responsibility. */
    void emit(AuditEvent event);

    /**
     * Audit event types. Sealed; B3 = {@link ChunkAdmissionRejected} only.
     */
    sealed interface AuditEvent {

        /**
         * Chunk admission rejection — emitted for B3 admission boundary rejections.
         *
         * <p><b>Emit boundary (Codex {@code 019e8df2} iter-3 P1.3 absorb):</b>
         * 400 invalid headers / format mismatch / body-read-error +
         * 404 session not found +
         * 413 OVERSIZE (declared or actual or bounded-read-limit) +
         * 415 FORMAT_REJECTED +
         * 409 INVALID_TRANSITION / OUT_OF_ORDER / IDEMPOTENCY_CONFLICT +
         * 429 QUEUE_FULL (dispatcher) +
         * 503 STT_UNAVAILABLE (dispatcher).
         *
         * <p><b>Authz failures (401 AUTH_INVALID, 403 owner mismatch / MEETING_FORBIDDEN)
         * B3 DIŞI</b> — auth audit ayrı security/audit boundary (separate PR).
         *
         * @param retryAfterSeconds {@code null} for non-retryable rejections (400/404/413/415/409);
         *                          positive long for 429/503 dispatcher retry hints
         */
        record ChunkAdmissionRejected(
                String sessionId,
                Long tenantId,
                Long userId,
                long chunkSeq,
                int httpStatus,
                String rejectionCode,
                Long retryAfterSeconds,
                String correlationId,
                long timestampMs
        ) implements AuditEvent {
        }
    }
}
