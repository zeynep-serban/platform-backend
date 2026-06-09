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

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
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
        props.getDispatcher().setStreamKeyPrefix("meeting:chunks:");
        props.getDispatcher().setStreamMaxLen(5);
        props.getDispatcher().setQueueFullRetryAfterSeconds(5);
        props.getDispatcher().setUnavailableRetryAfterSeconds(30);

        dispatcher = new RedisStreamsAudioChunkDispatcher(redis, props);
    }

    private ChunkDispatchCommand command() {
        final AudioChunkPayload payload = AudioChunkPayload.of(new byte[] {1, 2, 3}, "abc123hash");
        return new ChunkDispatchCommand(
                "sess-1", 42L, 7L, "meeting-1", "dev-1", "tr",
                AudioFormat.values()[0], 16_000, 1, 3L, 1_000L, "corr-1", payload);
    }

    @Test
    void acceptedWhenBelowCapacity() {
        when(streamOps.size("meeting:chunks:42")).thenReturn(2L);
        when(streamOps.add(anyString(), anyMap())).thenReturn(RecordId.of("1-0"));

        final DispatchOutcome out = dispatcher.dispatch(command());

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        verify(streamOps).add(eq("meeting:chunks:42"), anyMap());
    }

    @Test
    void queueFullWhenAtCapacity() {
        when(streamOps.size("meeting:chunks:42")).thenReturn(5L);

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
        verify(streamOps).add(eq("meeting:chunks:42"), captor.capture());
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
}
