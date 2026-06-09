package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Streams cross-server audio chunk dispatcher producer (PR-gw-01C, #106).
 *
 * <p>ADR-0031 §3.7 cross-server contract: platform-backend audio-gateway (producer) →
 * staging-sw Redis Streams → platform-ai live-stt-service (consumer, PR-stt-03 scope).
 * Active only when {@code audio.gateway.dispatcher.mode=redis}; otherwise the default
 * {@link NoOpAudioChunkDispatcher} is used. {@link Primary} so it wins injection over the
 * NoOp bean when both are present.
 *
 * <p><b>Outcome mapping (issue #106):</b>
 * <ul>
 *   <li>XADD success → {@link DispatchOutcome.Accepted}</li>
 *   <li>stream at capacity ({@code XLEN >= stream-max-len}) →
 *       {@link DispatchOutcome.QueueFull} (backpressure, retry-after 5s default)</li>
 *   <li>Redis unreachable / command failure →
 *       {@link DispatchOutcome.Unavailable} (retry-after 30s default)</li>
 * </ul>
 *
 * <p><b>PII boundary (ADR-0030 §3.7):</b> only the SHA-256 hash of the chunk and routing
 * metadata are written to the stream — never raw audio bytes or transcript content.
 *
 * <p><b>Idempotency:</b> Redis stream entry IDs must be monotonic {@code ms-seq}, so the
 * deterministic {@code sessionId:chunkSeq} identity is carried as a {@code messageId} field
 * for replay-safe consumer-side dedup, while Redis assigns the physical entry ID.
 *
 * <p><b>Backpressure, not trimming:</b> a full stream is rejected with QueueFull rather than
 * trimmed, so unread backlog applies real backpressure. Consumer-side XACK/trim is
 * live-stt-service scope (PR-stt-03), out of scope here.
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
        final String streamKey = cfg.getStreamKeyPrefix() + cmd.tenantId();
        try {
            final Long length = redis.opsForStream().size(streamKey);
            if (length != null && length >= cfg.getStreamMaxLen()) {
                log.warn(
                        "Redis stream at capacity; rejecting chunk QueueFull",
                        kv(cmd, "streamLen", String.valueOf(length)));
                return new DispatchOutcome.QueueFull(cfg.getQueueFullRetryAfterSeconds());
            }

            redis.opsForStream().add(streamKey, streamFields(cmd));
            return new DispatchOutcome.Accepted();
        } catch (final DataAccessException ex) {
            // Connectivity / command failure (WireGuard down, Redis outage, timeout).
            // PII-safe log: ids + class only, never payload/transcript.
            log.warn(
                    "Redis dispatch failed; marking Unavailable err={} {}",
                    ex.getClass().getSimpleName(),
                    kv(cmd, "streamKey", streamKey));
            return new DispatchOutcome.Unavailable(cfg.getUnavailableRetryAfterSeconds());
        }
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
