package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ChunkAdmissionResponse;

import java.util.Optional;

/**
 * Session lifecycle registry — bounded in-memory storage for PR-gw-01A + PR-gw-01B-core.
 *
 * <p>Codex {@code 019e8c26} + {@code 019e8d78} iter-2 AGREE: persistence iddiası YOK;
 * durability sonraki slice'larda Redis Streams (PR-gw-01C) ile gelir. Bu interface
 * restart sonrası kayıp davranışını contract olarak yazar.
 *
 * <p>Idempotency-Key scope:
 * <ul>
 *   <li>Start: {@code tenantId + userId + "POST" + "/sessions" + key}</li>
 *   <li>Chunk: {@code tenantId + userId + "POST" + "/chunks" + sessionId + chunkSeq + key} —
 *       PR-gw-01B-core domain method {@link #admitChunk(ChunkRecordCommand)} ile</li>
 * </ul>
 *
 * <p>Chunk admission contract (PR-gw-01B-core Codex {@code 019e8d78} iter-1 absorb):
 * strict contiguous {@code chunkSeq} (init -1 → first chunk 0, sonraki {@code last+1});
 * replay yalnız aynı key + aynı seq + aynı payload SHA-256; aksi halde 409.
 */
public interface AudioSessionRegistry {

    /**
     * Create a new session OR return existing record if {@code startIdempotencyKey} matches.
     */
    CreateOutcome create(SessionCreateCommand cmd);

    /** Read-only snapshot by id. */
    Optional<SessionRecord> get(String sessionId);

    /**
     * Admit a chunk for {@code sessionId}. Domain method — strict contiguous chunkSeq,
     * payload-hash bound idempotent replay, atomic STARTED → STREAMING transition on first
     * chunk, owner check.
     */
    ChunkOutcome admitChunk(ChunkRecordCommand cmd);

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

    // ----- Chunk command & outcome (PR-gw-01B-core) ------------------------

    record ChunkRecordCommand(
            String sessionId,
            Long tenantId,
            Long userId,
            String idempotencyKey,
            long chunkSeq,
            long chunkStartedAtMs,
            int byteLength,
            String payloadSha256,
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
