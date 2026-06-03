package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;

import java.util.Optional;

/**
 * Session lifecycle registry — bounded in-memory storage for PR-gw-01A.
 *
 * <p>Codex {@code 019e8c26} iter-2 AGREE: persistence iddiası YOK; durability sonraki
 * slice'larda Redis Streams (PR-gw-01C) ile gelir. Bu interface restart sonrası kayıp
 * davranışını contract olarak yazar.
 *
 * <p>Idempotency-Key scope (Codex iter-2): {@code tenantId + userId + method + route + key}.
 * Same key + same effective request → same {@link SessionRecord} (replay). Same key +
 * materially different request → {@link CreateOutcome.IdempotencyConflict}.
 */
public interface AudioSessionRegistry {

    /**
     * Create a new session OR return existing record if {@code startIdempotencyKey} matches
     * a previous successful start with the same {@code tenantId+userId+meetingId+deviceId+
     * language+audioFormat+sampleRateHz+channels} signature.
     */
    CreateOutcome create(SessionCreateCommand cmd);

    /** Read-only snapshot by id. */
    Optional<SessionRecord> get(String sessionId);

    /**
     * Mark session as FINISHED. Idempotent: same {@code finishIdempotencyKey} returns
     * {@link FinishOutcome.AlreadyFinished} without state mutation.
     */
    FinishOutcome finish(String sessionId, String finishIdempotencyKey, Long tenantId, Long userId);

    /** Number of currently-tracked sessions (any state). */
    int activeCount();

    /** Bounded capacity (configured via properties). */
    int capacity();

    // ----- Command & outcome carriers ---------------------------------------

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
