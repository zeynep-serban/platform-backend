package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ChunkAdmissionResponse;

import java.util.Optional;

/**
 * Session lifecycle registry — bounded in-memory storage.
 *
 * <p>Codex {@code 019e8c26} + {@code 019e8c09} + {@code 019e8d78} + {@code 019e8df2}
 * iter-2 AGREE: persistence iddiası YOK (PR-gw-01C Redis Streams durability); single
 * atomic {@code admitChunk(cmd, dispatcher)} surface — dispatcher reject ederse
 * registry state ilerletmez (B3 atomicity blocker absorb, veri kaybı kapısı kapalı).
 *
 * <p>Idempotency-Key scope:
 * <ul>
 *   <li>Start: {@code tenantId + userId + "POST" + "/sessions" + key}</li>
 *   <li>Chunk: {@code tenantId + userId + "POST" + "/chunks" + sessionId + chunkSeq + key}
 *       — replay path dispatcher'ı TEKRAR ÇAĞIRMAZ (Codex iter-2: replay = previously
 *       dispatched, lastChunkIdempotencyKey + lastChunkPayloadSha256 cache dispatch-
 *       accepted gate sonrası set'lenir)</li>
 * </ul>
 */
public interface AudioSessionRegistry {

    /**
     * Create a new session OR return existing record if {@code startIdempotencyKey} matches.
     */
    CreateOutcome create(SessionCreateCommand cmd);

    /** Read-only snapshot by id. */
    Optional<SessionRecord> get(String sessionId);

    /**
     * Atomic admit-chunk + dispatch (Codex {@code 019e8df2} iter-2 AGREE B3):
     * <ul>
     *   <li>Session lookup + owner + state validation</li>
     *   <li>Replay check (same key + same hash + same seq) → no dispatcher call</li>
     *   <li>Idempotency conflict / out-of-order → no dispatcher call</li>
     *   <li>Fresh chunk: dispatcher.dispatch → Accepted → mutate state; QueueFull/Unavailable
     *       → NO mutation (atomicity gate)</li>
     * </ul>
     */
    ChunkOutcome admitChunk(ChunkRecordCommand cmd, AudioChunkDispatcher dispatcher);

    /**
     * Mark session as FINISHED. Idempotent: same {@code finishIdempotencyKey} returns
     * {@link FinishOutcome.AlreadyFinished} without state mutation.
     */
    FinishOutcome finish(String sessionId, String finishIdempotencyKey, Long tenantId, Long userId);

    /** Number of currently-tracked sessions (any state). */
    int activeCount();

    /** Bounded capacity (configured via properties). */
    int capacity();

    // ----- Start command & outcome -----------------------------------------

    record SessionCreateCommand(
            Long tenantId,
            Long userId,
            String meetingId,
            String deviceId,
            String language,
            AudioFormat audioFormat,
            int sampleRateHz,
            int channels,
            String idempotencyKey,
            long sessionStartMs
    ) {
    }

    sealed interface CreateOutcome {
        record Created(SessionRecord record) implements CreateOutcome {
        }

        record Replayed(SessionRecord record) implements CreateOutcome {
        }

        record IdempotencyConflict(String existingSessionId) implements CreateOutcome {
        }

        record RegistryFull(int capacity) implements CreateOutcome {
        }
    }

    // ----- Chunk command & outcome (PR-gw-01B-core + B3 atomic) ------------

    /**
     * Chunk admission command — registry-facing input.
     *
     * <p>{@link #payload} is the bounded read-only buffer + SHA-256; dispatcher gets a
     * {@link AudioChunkDispatcher.ChunkDispatchCommand} built from {@link SessionRecord} +
     * this command's payload/correlationId (Codex {@code 019e8df2} iter-2: dispatcher
     * receives session-derived fields, NOT client headers).
     */
    record ChunkRecordCommand(
            String sessionId,
            Long tenantId,
            Long userId,
            String idempotencyKey,
            long chunkSeq,
            long chunkStartedAtMs,
            String correlationId,
            AudioChunkPayload payload,
            long nowMs
    ) {
    }

    sealed interface ChunkOutcome {
        record Accepted(SessionRecord record, ChunkAdmissionResponse response) implements ChunkOutcome {
        }

        record Replayed(ChunkAdmissionResponse response) implements ChunkOutcome {
        }

        record NotFound() implements ChunkOutcome {
        }

        record OwnerMismatch() implements ChunkOutcome {
        }

        record InvalidState(SessionState currentState) implements ChunkOutcome {
        }

        record OutOfOrder(long expectedChunkSeq, long actualChunkSeq) implements ChunkOutcome {
        }

        record IdempotencyConflict() implements ChunkOutcome {
        }

        /**
         * Dispatcher reported bounded queue full — caller should retry after the given
         * seconds. NO mutation (Codex {@code 019e8df2} iter-2 B3 atomicity gate).
         */
        record DispatchQueueFull(long retryAfterSeconds) implements ChunkOutcome {
        }

        /**
         * Dispatcher reported downstream STT unavailable — caller should retry after the
         * given seconds. NO mutation.
         */
        record DispatchUnavailable(long retryAfterSeconds) implements ChunkOutcome {
        }
    }

    // ----- Finish outcome --------------------------------------------------

    sealed interface FinishOutcome {
        record Finished(SessionRecord record) implements FinishOutcome {
        }

        record AlreadyFinished(SessionRecord record) implements FinishOutcome {
        }

        record NotFound() implements FinishOutcome {
        }

        record IdempotencyConflict(String existingFinishKey) implements FinishOutcome {
        }

        record OwnerMismatch() implements FinishOutcome {
        }
    }
}
