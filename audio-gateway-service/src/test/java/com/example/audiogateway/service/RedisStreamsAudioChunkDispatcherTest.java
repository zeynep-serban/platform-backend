package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.any;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamsAudioChunkDispatcherTest {

    private StreamOperations<String, Object, Object> streamOps;
    private RedisStreamsAudioChunkDispatcher dispatcher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDispatcher().setMode("redis");
        props.getDispatcher().setStreamKeyPrefix("audio:chunks:p");
        props.getDispatcher().setPartitionCount(32);
        props.getDispatcher().setStreamMaxLen(5);
        props.getDispatcher().setQueueFullRetryAfterSeconds(5);
        props.getDispatcher().setUnavailableRetryAfterSeconds(30);

        dispatcher = new RedisStreamsAudioChunkDispatcher(redis, props);
    }

    private ChunkDispatchCommand command() {
        return command("sess-1");
    }

    private ChunkDispatchCommand command(final String sessionId) {
        final AudioChunkPayload payload = AudioChunkPayload.of(new byte[] {1, 2, 3}, "abc123hash");
        return new ChunkDispatchCommand(
                sessionId, 42L, 7L, "meeting-1", "dev-1", "tr",
                AudioFormat.values()[0], 16_000, 1, 3L, 1_000L, "corr-1", payload);
    }

    /** Mirrors the dispatcher's partition formula (Codex 019e97bb absorb + ADR-0031 D3). */
    private static String expectedKey(final long tenantId, final String sessionId) {
        return "audio:chunks:p"
                + String.format("%02d", Math.floorMod((tenantId + sessionId).hashCode(), 32));
    }

    @Test
    void acceptedWhenBelowCapacity() {
        when(streamOps.size(expectedKey(42L, "sess-1"))).thenReturn(2L);
        when(streamOps.add(anyString(), anyMap())).thenReturn(RecordId.of("1-0"));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        verify(streamOps).add(eq(expectedKey(42L, "sess-1")), anyMap());
    }

    @Test
    void queueFullWhenAtCapacity() {
        when(streamOps.size(expectedKey(42L, "sess-1"))).thenReturn(5L);

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) out).retryAfterSeconds()).isEqualTo(5L);
        verify(streamOps, never()).add(anyString(), anyMap());
    }

    @Test
    void unavailableOnRedisFailure() {
        when(streamOps.size(anyString())).thenThrow(new QueryTimeoutException("redis down"));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(((DispatchOutcome.Unavailable) out).retryAfterSeconds()).isEqualTo(30L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void payloadCarriesHashNotRawAudio() {
        when(streamOps.size(anyString())).thenReturn(0L);
        when(streamOps.add(anyString(), anyMap())).thenReturn(RecordId.of("1-0"));

        dispatcher.dispatch(command());

        final ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq(expectedKey(42L, "sess-1")), captor.capture());
        final Map<String, String> fields = captor.getValue();
        assertThat(fields).containsEntry("sha256", "abc123hash");
        assertThat(fields).containsEntry("messageId", "sess-1:3");
        assertThat(fields).doesNotContainKey("bytes");
        assertThat(fields).doesNotContainKey("audio");
    }

    @Test
    void propertiesValidateAcceptsRedisMode() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDispatcher().setMode("redis");
        props.validate(); // must not throw — SUPPORTED_MODES now includes redis
    }

    @Test
    void propertiesValidateRejectsUnknownMode() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDispatcher().setMode("kafka");
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propertiesValidateRejectsInvalidPartitionCount() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDispatcher().setMode("redis");
        props.getDispatcher().setPartitionCount(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    /** Same tenant+session always lands on the same partition (consumer dedup determinism). */
    @Test
    void partitionIsDeterministicPerSession() {
        when(streamOps.size(anyString())).thenReturn(0L);
        when(streamOps.add(anyString(), anyMap())).thenReturn(RecordId.of("1-0"));

        dispatcher.dispatch(command("sess-1"));
        dispatcher.dispatch(command("sess-1"));

        verify(streamOps, org.mockito.Mockito.times(2)).add(eq(expectedKey(42L, "sess-1")), anyMap());
        assertThat(expectedKey(42L, "sess-1"))
                .matches("audio:chunks:p\\d{2}")
                .isEqualTo(expectedKey(42L, "sess-1"));
    }

    /** One tenant's sessions spread across partitions — per-tenant single-lane is gone. */
    @Test
    void sessionsOfOneTenantCanLandOnDifferentPartitions() {
        boolean differentPartitionSeen = false;
        final String first = expectedKey(42L, "sess-0");
        for (int i = 1; i <= 64 && !differentPartitionSeen; i++) {
            differentPartitionSeen = !first.equals(expectedKey(42L, "sess-" + i));
        }
        assertThat(differentPartitionSeen)
                .as("64 sessions of tenant 42 must not all hash to one partition")
                .isTrue();
    }

    // ─── P2-1 (review iter-2): Codex 8-scenario 429/503 + Retry-After enumeration ───

    /** Scenario 4: Redis AUTH/ACL denied → Unavailable(503) retry-after 30s (+ALERT log). */
    @Test
    void authFailureMapsToUnavailableThirtySeconds() {
        when(streamOps.size(anyString()))
                .thenThrow(new InvalidDataAccessApiUsageException("NOAUTH Authentication required."));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(((DispatchOutcome.Unavailable) out).retryAfterSeconds()).isEqualTo(30L);
        verify(streamOps, never()).add(anyString(), anyMap());
    }

    /** Scenario 5: mTLS handshake failure → Unavailable(503) retry-after 30s (+ALERT log). */
    @Test
    void mtlsHandshakeFailureMapsToUnavailableThirtySeconds() {
        when(streamOps.size(anyString())).thenThrow(new RedisConnectionFailureException(
                "Unable to connect", new SSLHandshakeException("mTLS handshake failed")));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(((DispatchOutcome.Unavailable) out).retryAfterSeconds()).isEqualTo(30L);
    }

    /** Scenario 7: transient cluster failover → Unavailable(503) with SHORT retry-after (5s). */
    @Test
    void clusterFailoverTransientMapsToUnavailableFiveSeconds() {
        when(streamOps.size(anyString())).thenThrow(new RedisSystemException(
                "CLUSTERDOWN Hash slot not served", new RuntimeException("CLUSTERDOWN")));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(((DispatchOutcome.Unavailable) out).retryAfterSeconds()).isEqualTo(5L);
    }

    /** Scenario 6: consumer group lag > threshold (XPENDING count) → QueueFull(429) 10s. */
    @Test
    void consumerLagBeyondThresholdMapsToQueueFullTenSeconds() {
        when(streamOps.size(anyString())).thenReturn(1L);
        final PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(20_000L);
        when(streamOps.pending(anyString(), eq("live-stt-v1"))).thenReturn(summary);

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) out).retryAfterSeconds()).isEqualTo(10L);
        verify(streamOps, never()).add(anyString(), anyMap());
    }

    /** Scenario 8: consumer not draining (oldest pending idle > threshold) → QueueFull(429) 10s. */
    @Test
    void consumerNotDrainingMapsToQueueFullTenSeconds() {
        when(streamOps.size(anyString())).thenReturn(1L);
        final PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(summary.getTotalPendingMessages()).thenReturn(3L); // below count threshold
        when(streamOps.pending(anyString(), eq("live-stt-v1"))).thenReturn(summary);

        final PendingMessage oldest = mock(PendingMessage.class);
        when(oldest.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(2)); // > 60s
        final PendingMessages oldestPage = mock(PendingMessages.class);
        when(oldestPage.isEmpty()).thenReturn(false);
        when(oldestPage.get(0)).thenReturn(oldest);
        when(streamOps.pending(anyString(), eq("live-stt-v1"), any(Range.class), eq(1L)))
                .thenReturn(oldestPage);

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) out).retryAfterSeconds()).isEqualTo(10L);
        verify(streamOps, never()).add(anyString(), anyMap());
    }
}
