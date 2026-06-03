package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * Bounded in-memory implementation of {@link AudioSessionRegistry}.
 *
 * <p>PR-gw-01A scope (Codex {@code 019e8c26} iter-3 REVISE absorb): no Redis, no persistence;
 * restart loses all sessions. Durability sonraki slice'da Redis Streams (PR-gw-01C) ile gelir.
 *
 * <p>Idempotency replay: key = {@code tenantId|userId|"POST"|"/sessions"|idempotencyKey};
 * value = sessionId + signature. Bounded by {@code audio.gateway.idempotency.replay-cache-size}.
 * When cap reached, oldest entry evicted via {@link LinkedHashMap} insertion-order policy
 * (synchronized for thread-safety).
 *
 * <p><b>Concurrency contract (Codex iter-3 P1 absorb):</b> {@code create(...)} is fully
 * {@code synchronized} on the monitor; concurrent calls with the same Idempotency-Key cannot
 * produce two distinct sessions. Capacity check is in the same critical section so the
 * registry never exceeds {@code maxActiveSessions}. Finish path uses CAS
 * ({@code sessions.replace(id, existing, finished)}) which is safe lock-free.
 */
@Service
public class InMemoryAudioSessionRegistry implements AudioSessionRegistry {

    private final AudioGatewayProperties props;
    private final ConcurrentMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    /**
     * Insertion-order bounded replay cache (Codex iter-3 P2 absorb). Synchronized via
     * {@link Collections#synchronizedMap} because {@link LinkedHashMap} is not thread-safe.
     */
    private final Map<String, IdempotencyEntry> startReplay;

    public InMemoryAudioSessionRegistry(final AudioGatewayProperties props) {
        this.props = props;
        final int cap = Math.max(1, props.getIdempotency().getReplayCacheSize());
        this.startReplay = Collections.synchronizedMap(
                new LinkedHashMap<String, IdempotencyEntry>(cap, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(final Map.Entry<String, IdempotencyEntry> eldest) {
                        return size() > cap;
                    }
                });
    }

    @Override
    public synchronized CreateOutcome create(final SessionCreateCommand cmd) {
        final String idempKey = startReplayKey(cmd.tenantId(), cmd.userId(), cmd.idempotencyKey());
        final String signature = startSignature(cmd);

        final IdempotencyEntry existing = startReplay.get(idempKey);
        if (existing != null) {
            if (!Objects.equals(existing.signature(), signature)) {
                return new CreateOutcome.IdempotencyConflict(existing.sessionId());
            }
            final SessionRecord rec = sessions.get(existing.sessionId());
            if (rec != null) {
                return new CreateOutcome.Replayed(rec);
            }
            // record evicted but replay cache still present → treat as fresh create
            startReplay.remove(idempKey);
        }

        if (sessions.size() >= props.getBounds().getMaxActiveSessions()) {
            return new CreateOutcome.RegistryFull(props.getBounds().getMaxActiveSessions());
        }

        final String sessionId = "SES-" + UUID.randomUUID();
        final SessionRecord rec = new SessionRecord(
                sessionId, cmd.tenantId(), cmd.userId(), cmd.meetingId(), cmd.deviceId(),
                cmd.language(), cmd.audioFormat(), cmd.sampleRateHz(), cmd.channels(),
                cmd.idempotencyKey(), cmd.sessionStartMs(),
                SessionState.STARTED, null, 0L, cmd.sessionStartMs());

        sessions.put(sessionId, rec);
        startReplay.put(idempKey, new IdempotencyEntry(sessionId, signature));

        return new CreateOutcome.Created(rec);
    }

    @Override
    public Optional<SessionRecord> get(final String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public FinishOutcome finish(final String sessionId, final String finishIdempotencyKey,
                                final Long tenantId, final Long userId) {
        final SessionRecord existing = sessions.get(sessionId);
        if (existing == null) {
            return new FinishOutcome.NotFound();
        }
        if (!Objects.equals(existing.tenantId(), tenantId)
                || !Objects.equals(existing.userId(), userId)) {
            return new FinishOutcome.OwnerMismatch();
        }
        if (existing.state() == SessionState.FINISHED) {
            if (Objects.equals(existing.finishIdempotencyKey(), finishIdempotencyKey)) {
                return new FinishOutcome.AlreadyFinished(existing);
            }
            return new FinishOutcome.IdempotencyConflict(existing.finishIdempotencyKey());
        }

        final long now = System.currentTimeMillis();
        final SessionRecord finished = existing.withFinish(finishIdempotencyKey, now);
        if (!sessions.replace(sessionId, existing, finished)) {
            final SessionRecord current = sessions.get(sessionId);
            if (current != null && current.state() == SessionState.FINISHED
                    && Objects.equals(current.finishIdempotencyKey(), finishIdempotencyKey)) {
                return new FinishOutcome.AlreadyFinished(current);
            }
            return new FinishOutcome.IdempotencyConflict(
                    current != null ? current.finishIdempotencyKey() : null);
        }
        return new FinishOutcome.Finished(finished);
    }

    @Override
    public int activeCount() {
        return sessions.size();
    }

    @Override
    public int capacity() {
        return props.getBounds().getMaxActiveSessions();
    }

    // ----- internals --------------------------------------------------------

    private static String startReplayKey(final Long tenantId, final Long userId, final String key) {
        return tenantId + "|" + userId + "|POST|/sessions|" + key;
    }

    private static String startSignature(final SessionCreateCommand cmd) {
        return cmd.meetingId() + "|" + cmd.deviceId() + "|" + cmd.language() + "|"
                + cmd.audioFormat() + "|" + cmd.sampleRateHz() + "|" + cmd.channels();
    }

    private record IdempotencyEntry(String sessionId, String signature) {
    }
}
