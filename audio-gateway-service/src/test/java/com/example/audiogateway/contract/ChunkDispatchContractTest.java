package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ChunkAdmissionResponse;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.service.AudioChunkDispatcher;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — PR-gw-01B3 dispatcher + audit + 429/503/Retry-After.
 *
 * <p>ADR-0031 + Codex {@code 019e8df2} iter-2 AGREE:
 * <ul>
 *   <li>Atomic admission: QueueFull/Unavailable → no registry mutation</li>
 *   <li>Retry-After header (config + outcome propagation)</li>
 *   <li>safeEmit audit sink (Exception catch, Throwable YASAK)</li>
 *   <li>ChunkAdmissionRejected emit for 413/415/409/429/503</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class ChunkDispatchContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";

    @Autowired
    private WebTestClient client;

    @Autowired
    private SettableAudioChunkDispatcher fakeDispatcher;

    @Autowired
    private RecordingAudioGatewayAuditSink auditSink;

    @BeforeEach
    void resetFakes() {
        fakeDispatcher.reset();
        auditSink.reset();
    }

    @TestConfiguration
    static class FakeBeans {
        @Bean
        @Primary
        public SettableAudioChunkDispatcher settableAudioChunkDispatcher() {
            return new SettableAudioChunkDispatcher();
        }

        @Bean
        @Primary
        public RecordingAudioGatewayAuditSink recordingAudioGatewayAuditSink() {
            return new RecordingAudioGatewayAuditSink();
        }
    }

    /** Test fake: settable outcome. */
    static class SettableAudioChunkDispatcher implements AudioChunkDispatcher {
        private final AtomicReference<DispatchOutcome> next =
                new AtomicReference<>(new DispatchOutcome.Accepted());
        private final java.util.concurrent.atomic.AtomicInteger callCount =
                new java.util.concurrent.atomic.AtomicInteger();

        public void setOutcome(final DispatchOutcome outcome) {
            this.next.set(outcome);
        }

        public int callCount() {
            return callCount.get();
        }

        public void reset() {
            this.next.set(new DispatchOutcome.Accepted());
            this.callCount.set(0);
        }

        @Override
        public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
            callCount.incrementAndGet();
            return next.get();
        }
    }

    /** Test fake: records emitted events. */
    static class RecordingAudioGatewayAuditSink implements AudioGatewayAuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        public List<AuditEvent> events() {
            return List.copyOf(events);
        }

        public void reset() {
            events.clear();
        }

        @Override
        public void emit(final AuditEvent event) {
            events.add(event);
        }
    }

    // ----- helpers ---------------------------------------------------------

    private static StartSessionRequest validStartRequest() {
        return new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 1);
    }

    private WebTestClient asUser(final long companyId, final long userId) {
        return client.mutateWith(mockJwt()
                .jwt(j -> j.claim("companyId", companyId).claim("userId", userId)));
    }

    private String startFresh(final long companyId, final long userId, final String idempValue) {
        return asUser(companyId, userId)
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, idempValue)
                .bodyValue(validStartRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();
    }

    private static byte[] payload(final int size) {
        final byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i & 0xFF);
        }
        return bytes;
    }

    private WebTestClient.RequestHeadersSpec<?> chunkRequest(
            final long companyId, final long userId,
            final String sessionId, final String chunkIdemp,
            final long chunkSeq, final byte[] body, final AudioFormat format,
            final int sampleRateHz, final int channels) {
        return asUser(companyId, userId)
                .post().uri(SESSIONS_PATH + "/" + sessionId + "/chunks")
                .header(IDEMP_HEADER, chunkIdemp)
                .header("X-Audio-Chunk-Seq", String.valueOf(chunkSeq))
                .header("X-Audio-Chunk-Started-At-Ms", String.valueOf(1_781_820_000_000L + chunkSeq))
                .header("X-Audio-Format", format.name())
                .header("X-Audio-Sample-Rate-Hz", String.valueOf(sampleRateHz))
                .header("X-Audio-Channels", String.valueOf(channels))
                .header("X-Audio-Byte-Length", String.valueOf(body.length))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(body);
    }

    // ---------------- Atomic admission tests ----------------

    /**
     * Codex {@code 019e8df2} iter-1 P1 absorb zorunlu test:
     * QueueFull → no registry mutation → status chunkCount=0 → dispatcher Accepted →
     * same chunkSeq=0 retry → 200 + replayed=false + chunkCount=1.
     */
    @Test
    void dispatcherQueueFull_registryDoesNotProgress_thenAcceptedRetrySucceeds() {
        final String sid = startFresh(1L, 42L, "b3-queuefull-fix-001-aaa");
        final byte[] body = payload(96);

        // 1. Dispatcher QueueFull(5) → 429 + Retry-After: 5
        fakeDispatcher.setOutcome(new DispatchOutcome.QueueFull(5));
        chunkRequest(1L, 42L, sid, "b3-queuefull-idemp-fix-001a", 0, body,
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_QUEUE_FULL);
                    assertThat(err.retryable()).isTrue();
                });

        // 2. Audit emit (429 + retryAfter=5)
        assertThat(auditSink.events())
                .filteredOn(e -> e instanceof AuditEvent.ChunkAdmissionRejected r
                        && r.httpStatus() == 429)
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    AuditEvent.ChunkAdmissionRejected r = (AuditEvent.ChunkAdmissionRejected) e;
                    assertThat(r.rejectionCode()).isEqualTo(ErrorResponse.CODE_QUEUE_FULL);
                    assertThat(r.retryAfterSeconds()).isEqualTo(5L);
                    assertThat(r.chunkSeq()).isZero();
                });

        // 3. Status: chunkCount=0, lastChunkSeq=0 (init -1 fallback)
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.chunkCount").isEqualTo(0)
                .jsonPath("$.state").isEqualTo("STARTED");

        // 4. Dispatcher Accepted; same chunkSeq=0 retry → 200 replayed=false chunkCount=1
        fakeDispatcher.setOutcome(new DispatchOutcome.Accepted());
        chunkRequest(1L, 42L, sid, "b3-queuefull-idemp-fix-001b", 0, body,
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChunkAdmissionResponse.class)
                .value(resp -> {
                    assertThat(resp.replayed()).isFalse();
                    assertThat(resp.chunkCount()).isEqualTo(1L);
                    assertThat(resp.chunkSeq()).isZero();
                });

        // 5. Final status STREAMING + chunkCount=1
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.chunkCount").isEqualTo(1)
                .jsonPath("$.state").isEqualTo("STREAMING");
    }

    /**
     * Unavailable kısa varyantı: 503 + Retry-After:30 + audit emit.
     */
    @Test
    void dispatcherUnavailable_returns503_RetryAfter30_andAuditEmit() {
        final String sid = startFresh(1L, 42L, "b3-unavail-fix-001-bbb");
        fakeDispatcher.setOutcome(new DispatchOutcome.Unavailable(30));

        chunkRequest(1L, 42L, sid, "b3-unavail-idemp-fix-001b", 0, payload(64),
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_STT_UNAVAILABLE);
                    assertThat(err.retryable()).isTrue();
                });

        assertThat(auditSink.events())
                .filteredOn(e -> e instanceof AuditEvent.ChunkAdmissionRejected r
                        && r.httpStatus() == 503)
                .hasSize(1);
    }

    // ---------------- Audit emission coverage ----------------

    @Test
    void auditSink_emits_for_format_rejected_415() {
        final String sid = startFresh(1L, 42L, "b3-fmt-rej-fix-001-ccc");
        // Session WAV; chunk header MP3 → 415 FORMAT_REJECTED (before dispatcher)
        chunkRequest(1L, 42L, sid, "b3-fmt-rej-idemp-fix-001c", 0, payload(32),
                AudioFormat.MP3, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(415);

        assertThat(auditSink.events())
                .filteredOn(e -> e instanceof AuditEvent.ChunkAdmissionRejected r
                        && r.httpStatus() == 415)
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    AuditEvent.ChunkAdmissionRejected r = (AuditEvent.ChunkAdmissionRejected) e;
                    assertThat(r.rejectionCode()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED);
                    assertThat(r.retryAfterSeconds()).isNull();
                });
    }

    @Test
    void auditSink_emits_for_session_not_found_404() {
        chunkRequest(1L, 42L, "SES-not-here", "b3-notfound-idemp-fix-001d", 0,
                payload(32), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isNotFound();

        assertThat(auditSink.events())
                .filteredOn(e -> e instanceof AuditEvent.ChunkAdmissionRejected r
                        && r.httpStatus() == 404)
                .hasSize(1);
    }

    @Test
    void auditSink_does_NOT_emit_for_happy_path() {
        final String sid = startFresh(1L, 42L, "b3-happy-fix-001-eee");
        // Default fakeDispatcher Accepted
        chunkRequest(1L, 42L, sid, "b3-happy-idemp-fix-001e", 0, payload(64),
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();

        // No audit emission for 200 happy path
        assertThat(auditSink.events()).isEmpty();
    }

    // ---------------- Dispatcher call discipline ----------------

    @Test
    void replay_path_does_NOT_call_dispatcher_again() {
        final String sid = startFresh(1L, 42L, "b3-replay-fix-001-fff");
        final byte[] body = payload(80);
        final String idemp = "b3-replay-idemp-fix-001f";

        // 1st call: dispatcher Accepted → state mutate
        chunkRequest(1L, 42L, sid, idemp, 0, body, AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();
        final int firstCallCount = fakeDispatcher.callCount();

        // 2nd call same key + same payload → replay (no dispatcher call)
        chunkRequest(1L, 42L, sid, idemp, 0, body, AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChunkAdmissionResponse.class)
                .value(r -> assertThat(r.replayed()).isTrue());

        // Dispatcher call count unchanged (replay path bypasses dispatcher)
        assertThat(fakeDispatcher.callCount()).isEqualTo(firstCallCount);
    }
}
