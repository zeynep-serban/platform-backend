package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Streams cross-server audio chunk dispatcher producer (PR-gw-01C, #106).
 *
 * <p>ADR-0031 D2 cross-server network topology: platform-backend audio-gateway (producer) →
 * staging-sw Redis Streams → platform-ai live-stt-service (consumer, PR-stt-04 scope —
 * platform-ai#137, implemented in platform-ai#138).
 * Failure modes per ADR-0031 D8. Active only when
 * {@code audio.gateway.dispatcher.mode=redis}; otherwise the default
 * {@link NoOpAudioChunkDispatcher} is used. {@link Primary} so it wins injection over the
 * NoOp bean when both are present.
 *
 * <p><b>Stream key partitioning (Codex 019e97bb iter-1 absorb + ADR-0031 D3):</b>
 * {@code audio:chunks:p00..pNN} — partition = {@code hash(tenantId + sessionId) %
 * partition-count} (default 32). Per-tenant keys were rejected: 100+ tenants explode the
 * keyspace and reduce each tenant to a single non-scalable consumer lane; fixed partitions
 * keep the {@code live-stt-v1} consumer group horizontally scalable and make
 * consumer-side dedup partition-deterministic (a session always lands on one partition).
 *
 * <p><b>Outcome mapping (issue #106 + Codex 8-scenario enumeration, review iter-2 P2-1):</b>
 * <ul>
 *   <li>XADD success → {@link DispatchOutcome.Accepted}</li>
 *   <li>stream at capacity ({@code XLEN >= stream-max-len}) →
 *       {@link DispatchOutcome.QueueFull} (backpressure, retry-after 5s default)</li>
 *   <li>consumer group lag &gt; threshold (XPENDING count) →
 *       {@link DispatchOutcome.QueueFull} (retry-after 10s default)</li>
 *   <li>consumer not draining (oldest pending idle &gt; threshold) →
 *       {@link DispatchOutcome.QueueFull} (retry-after 10s default)</li>
 *   <li>Redis AUTH/ACL failure → {@link DispatchOutcome.Unavailable}
 *       (retry-after 30s, ALERT-level log for ops routing)</li>
 *   <li>mTLS/SSL handshake failure → {@link DispatchOutcome.Unavailable}
 *       (retry-after 30s, ALERT-level log)</li>
 *   <li>cluster failover transient (MOVED/CLUSTERDOWN/LOADING/TRYAGAIN) →
 *       {@link DispatchOutcome.Unavailable} (short retry-after, 5s default)</li>
 *   <li>connection refused / WireGuard down / other command failure →
 *       {@link DispatchOutcome.Unavailable} (retry-after 30s default)</li>
 * </ul>
 *
 * <p><b>Audit note (review M-1, follow-up):</b> the success path does not yet emit the
 * {@code audio_chunk_forwarded_to_platform_ai} audit event required by ADR-0030
 * §"Cross-Server STT Transit Boundary" for the KVKK m.5/m.10 lawful-basis trail; the B3
 * audit sink currently covers only admission rejections (429/503). Extending the sink with
 * a {@code ChunkForwardedToComputePlane} variant (per-session batch emit) is a documented
 * follow-up — see the PR review thread.
 *
 * <p><b>PII boundary (ADR-0030 §"Cross-Server STT Transit Boundary"):</b> only the SHA-256
 * hash of the chunk and routing metadata are written to the stream — never raw audio bytes
 * or transcript content.
 *
 * <p><b>Idempotency / consumer dedup contract:</b> Redis stream entry IDs must be monotonic
 * {@code ms-seq}, so the deterministic {@code sessionId:chunkSeq} identity is carried as a
 * {@code messageId} field while Redis assigns the physical entry ID. The consumer MUST read
 * the {@code messageId} field (NOT the Redis stream entry ID) for replay-safe deduplication:
 * entry IDs are Redis-assigned timestamps and carry no session/chunk identity.
 *
 * <p><b>Backpressure, not trimming:</b> a full stream is rejected with QueueFull rather than
 * trimmed, so unread backlog applies real backpressure. Consumer-side XACK/trim is
 * live-stt-service scope (PR-stt-04, platform-ai#137/#138), out of scope here.
 *
 * <p><b>Concurrency note (AudioChunkDispatcher C-debt):</b> {@code dispatch} runs under the
 * registry admission monitor. XLEN + XADD are two round-trips; under a single gateway and
 * the synchronized monitor this is acceptable for the PoC. A high-throughput refactor
 * (per-session lock / outbox) is the documented follow-up.
 */
@Service
@Primary
@ConditionalOnProperty(name = "audio.gateway.dispatcher.mode", havingValue = "redis")
public class RedisStreamsAudioChunkDispatcher implements AudioChunkDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsAudioChunkDispatcher.class);

    private final StringRedisTemplate redis;
    private final AudioGatewayProperties.Dispatcher cfg;

    public RedisStreamsAudioChunkDispatcher(
            final StringRedisTemplate redis, final AudioGatewayProperties properties) {
        this.redis = redis;
        this.cfg = properties.getDispatcher();
    }

    @Override
    public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
        final String streamKey = streamKeyFor(cmd);
        try {
            final Long length = redis.opsForStream().size(streamKey);
            // length == null → stream does not exist yet → first XADD creates it (Accepted path).
            if (length != null && length >= cfg.getStreamMaxLen()) {
                log.warn(
                        "Redis stream at capacity; rejecting chunk QueueFull",
                        kv(cmd, "streamLen", String.valueOf(length)));
                return new DispatchOutcome.QueueFull(cfg.getQueueFullRetryAfterSeconds());
            }

            final DispatchOutcome lagOutcome = consumerLagGate(streamKey, cmd);
            if (lagOutcome != null) {
                return lagOutcome;
            }

            redis.opsForStream().add(streamKey, streamFields(cmd));
            return new DispatchOutcome.Accepted();
        } catch (final DataAccessException ex) {
            return classifyFailure(ex, streamKey, cmd);
        }
    }

    /**
     * Producer-side consumer-health gate (Codex scenarios 6 + 8, review iter-2 P2-1):
     * XPENDING introspection on the {@code live-stt-v1} consumer group. Lag beyond the
     * pending-count threshold, or an oldest entry idling beyond the idle threshold
     * (consumer not draining), both map to QueueFull with the lag retry-after (10s default)
     * — a softer signal than a hard outage.
     *
     * <p>Pre-consumer phase (PR-stt-04 not deployed yet, group absent): XPENDING raises;
     * that is swallowed and the XLEN gate stays the only backpressure signal.
     */
    private DispatchOutcome consumerLagGate(final String streamKey, final ChunkDispatchCommand cmd) {
        final PendingMessagesSummary pending;
        try {
            pending = redis.opsForStream().pending(streamKey, cfg.getConsumerGroup());
        } catch (final DataAccessException ex) {
            // Consumer group not created yet — expected until the live-stt consumer ships.
            return null;
        }
        if (pending == null) {
            return null;
        }
        if (pending.getTotalPendingMessages() > cfg.getConsumerLagPendingThreshold()) {
            log.warn(
                    "Consumer group lag beyond threshold; rejecting chunk QueueFull {}",
                    kv(cmd, "pendingCount", String.valueOf(pending.getTotalPendingMessages())));
            return new DispatchOutcome.QueueFull(cfg.getConsumerLagRetryAfterSeconds());
        }
        if (pending.getTotalPendingMessages() > 0 && oldestPendingIdleMs(streamKey) > cfg.getConsumerIdleThresholdMs()) {
            log.warn(
                    "Consumer not draining (oldest pending idle beyond threshold); QueueFull {}",
                    kv(cmd, "pendingCount", String.valueOf(pending.getTotalPendingMessages())));
            return new DispatchOutcome.QueueFull(cfg.getConsumerLagRetryAfterSeconds());
        }
        return null;
    }

    /** Oldest pending entry's idle time in ms, or 0 when it cannot be determined. */
    private long oldestPendingIdleMs(final String streamKey) {
        try {
            final PendingMessages oldest = redis.opsForStream()
                    .pending(streamKey, cfg.getConsumerGroup(), Range.unbounded(), 1L);
            if (oldest == null || oldest.isEmpty()) {
                return 0L;
            }
            return oldest.get(0).getElapsedTimeSinceLastDelivery().toMillis();
        } catch (final DataAccessException ex) {
            return 0L;
        }
    }

    /**
     * Exception type-dispatch (Codex scenarios 3/4/5/7, review iter-2 P2-1). PII-safe
     * logging throughout: ids + exception class only, never payload/transcript.
     * AUTH/ACL and mTLS failures log at ERROR with an {@code ALERT} marker for ops
     * alert routing; transient cluster failover gets the short failover retry-after.
     */
    private DispatchOutcome classifyFailure(
            final DataAccessException ex, final String streamKey, final ChunkDispatchCommand cmd) {
        final String chain = causeChain(ex);
        if (chain.contains("NOAUTH") || chain.contains("WRONGPASS") || chain.contains("NOPERM")
                || chain.contains("AUTHENTICATION")) {
            log.error(
                    "ALERT Redis AUTH/ACL failure on dispatch err={} {}",
                    ex.getClass().getSimpleName(),
                    kv(cmd, "streamKey", streamKey));
            return new DispatchOutcome.Unavailable(cfg.getUnavailableRetryAfterSeconds());
        }
        if (hasSslCause(ex)) {
            log.error(
                    "ALERT mTLS/SSL handshake failure on dispatch err={} {}",
                    ex.getClass().getSimpleName(),
                    kv(cmd, "streamKey", streamKey));
            return new DispatchOutcome.Unavailable(cfg.getUnavailableRetryAfterSeconds());
        }
        if (chain.contains("MOVED") || chain.contains("CLUSTERDOWN") || chain.contains("LOADING")
                || chain.contains("TRYAGAIN") || chain.contains("MASTERDOWN") || chain.contains("FAILOVER")) {
            log.warn(
                    "Redis transient cluster failover; short retry err={} {}",
                    ex.getClass().getSimpleName(),
                    kv(cmd, "streamKey", streamKey));
            return new DispatchOutcome.Unavailable(cfg.getFailoverRetryAfterSeconds());
        }
        // Connection refused / WireGuard down / timeout / anything else.
        log.warn(
                "Redis dispatch failed; marking Unavailable err={} {}",
                ex.getClass().getSimpleName(),
                kv(cmd, "streamKey", streamKey));
        return new DispatchOutcome.Unavailable(cfg.getUnavailableRetryAfterSeconds());
    }

    /** Upper-cased class names + messages of the whole cause chain (keyword matching). */
    private static String causeChain(final Throwable ex) {
        final StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(' ');
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean hasSslCause(final Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Partition-based stream key (ADR-0031 D3): {@code prefix + zero-padded(hash(tenantId +
     * sessionId) % partitionCount)}. Deterministic per session, so all chunks of one session
     * land on the same partition and consumer-side dedup stays partition-local.
     */
    private String streamKeyFor(final ChunkDispatchCommand cmd) {
        final int partition = Math.floorMod(
                (cmd.tenantId() + cmd.sessionId()).hashCode(), cfg.getPartitionCount());
        return cfg.getStreamKeyPrefix() + String.format("%02d", partition);
    }

    /** PII-safe stream payload: SHA-256 hash + routing metadata, never raw audio. */
    private static Map<String, String> streamFields(final ChunkDispatchCommand cmd) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("messageId", cmd.sessionId() + ":" + cmd.chunkSeq());
        fields.put("sessionId", cmd.sessionId());
        fields.put("chunkSeq", Long.toString(cmd.chunkSeq()));
        fields.put("tenantId", String.valueOf(cmd.tenantId()));
        fields.put("userId", String.valueOf(cmd.userId()));
        fields.put("meetingId", nullSafe(cmd.meetingId()));
        fields.put("deviceId", nullSafe(cmd.deviceId()));
        fields.put("language", nullSafe(cmd.language()));
        fields.put("audioFormat", cmd.audioFormat() == null ? "" : cmd.audioFormat().name());
        fields.put("sampleRateHz", Integer.toString(cmd.sampleRateHz()));
        fields.put("channels", Integer.toString(cmd.channels()));
        fields.put("chunkStartedAtMs", Long.toString(cmd.chunkStartedAtMs()));
        fields.put("correlationId", nullSafe(cmd.correlationId()));
        // hash ONLY — no audio bytes, no transcript (ADR-0030 PII boundary)
        fields.put("sha256", cmd.payload().sha256());
        fields.put("length", Integer.toString(cmd.payload().length()));
        return fields;
    }

    private static String kv(final ChunkDispatchCommand cmd, final String extraKey, final String extraVal) {
        return "sessionId=" + cmd.sessionId()
                + " chunkSeq=" + cmd.chunkSeq()
                + " correlationId=" + nullSafe(cmd.correlationId())
                + " " + extraKey + "=" + extraVal;
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }
}
