package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;

/**
 * Audio chunk dispatcher — Gateway → STT internal contract port.
 *
 * <p>3-AI mutabakat + Codex {@code 019e8df2} iter-2 AGREE PR-gw-01B3 scope: STT compute
 * worker ({@code platform-ai}) doğrudan client'a erişemez; yalnız Gateway üzerinden
 * cross-server dispatch (ADR-0031 §D2). Mock {@code NoOpAudioChunkDispatcher} default;
 * gerçek Redis Streams producer sonraki PR-gw-01C scope.
 *
 * <p><b>Concurrency contract (Codex iter-2):</b> B3 NoOp/mock için hızlı, synchronous,
 * registry'ye callback yapmayan port. {@link AudioSessionRegistry#admitChunk} synchronized
 * monitor altında çağrılır — implementation HIZLI olmalı (millisecond-level), aksi halde
 * tüm session admission throughput'u block eder. <b>C/PR-gw-01C borç notu:</b> Redis
 * producer global monitor altında yavaş/blocking hale gelirse per-session lock,
 * outbox/idempotent stream key veya farklı admission coordinator refactor'ı gerekir.
 *
 * <p>Internal headers (Gateway-derived) — client'tan ASLA trust edilmez (ADR-0031 §D2):
 * X-Correlation-Id + X-Meeting-Id + X-Session-Id + X-Device-Id + X-Tenant-Id (JWT-derived)
 * + X-User-Id (JWT-derived) + language + audio_metadata.
 */
public interface AudioChunkDispatcher {

    /**
     * Attempt to dispatch a chunk downstream. Synchronous; returns outcome.
     *
     * <p>B3 acceptance contract: {@link DispatchOutcome.Accepted} → registry commits state;
     * {@link DispatchOutcome.QueueFull} / {@link DispatchOutcome.Unavailable} → registry
     * does NOT mutate (atomicity gate; Codex iter-1 P1 absorb).
     */
    DispatchOutcome dispatch(ChunkDispatchCommand cmd);

    /**
     * Gateway-built command — fields derived from session record + JWT (NOT client headers).
     * Codex {@code 019e8df2} iter-2 absorb: meetingId/deviceId/language/audioFormat/
     * sampleRateHz/channels SessionRecord'tan; correlationId controller'dan.
     */
    record ChunkDispatchCommand(
            String sessionId,
            Long tenantId,
            Long userId,
            String meetingId,
            String deviceId,
            String language,
            AudioFormat audioFormat,
            int sampleRateHz,
            int channels,
            long chunkSeq,
            long chunkStartedAtMs,
            String correlationId,
            AudioChunkPayload payload
    ) {
    }

    /** Dispatch outcome — sealed; B3 has 3 cases (Codex iter-2 AGREE). */
    sealed interface DispatchOutcome {
        /** Downstream accepted; registry commits state. */
        record Accepted() implements DispatchOutcome {
        }

        /**
         * Bounded queue full / admission rate exceeded — caller should retry after the
         * given seconds. Codex {@code 019e8df2} iter-2: compact constructor enforces
         * {@code retryAfterSeconds > 0}.
         */
        record QueueFull(long retryAfterSeconds) implements DispatchOutcome {
            public QueueFull {
                if (retryAfterSeconds <= 0) {
                    throw new IllegalArgumentException("retryAfterSeconds must be positive");
                }
            }
        }

        /**
         * Downstream STT unavailable (cross-server connectivity / dependency outage) —
         * caller should retry after the given seconds.
         */
        record Unavailable(long retryAfterSeconds) implements DispatchOutcome {
            public Unavailable {
                if (retryAfterSeconds <= 0) {
                    throw new IllegalArgumentException("retryAfterSeconds must be positive");
                }
            }
        }
    }
}
