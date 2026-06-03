package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ChunkAdmissionResponse;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.StatusResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — POST /api/v1/audio-gateway/sessions/{sessionId}/chunks.
 *
 * <p>ADR-0031 + Codex {@code 019e8d78} iter-2 AGREE PR-gw-01B-core (B1+B2 combined):
 * <ul>
 *   <li>Binary body + X-Audio-* headers canonical (multipart YASAK)</li>
 *   <li>Strict contiguous chunkSeq (init -1 → first 0 → next last+1)</li>
 *   <li>SHA-256 payload hash + bounded body aggregation</li>
 *   <li>Atomic admitChunk via registry domain method</li>
 *   <li>Idempotent replay (same key + same seq + same hash) → 200</li>
 *   <li>Out-of-order / gap → 409 CHUNK_OUT_OF_ORDER</li>
 *   <li>Same seq + different key/payload → 409 IDEMPOTENCY_CONFLICT</li>
 *   <li>Format/sample/channels session match → 415 if mismatch</li>
 *   <li>Declared/actual byte length mismatch → 400; oversize → 413</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class ChunkAdmissionContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";

    @Autowired
    private WebTestClient client;

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

    // ---------------- Validation ----------------

    @Test
    void noAuth_returns401() {
        client.post().uri(SESSIONS_PATH + "/SES-x/chunks")
                .header(IDEMP_HEADER, "chunk-no-auth-fixture-001")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(1))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void missingIdempotencyValue_returns400() {
        final String sid = startFresh(1L, 42L, "chunk-no-idemp-start-fix-001");
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/chunks")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(1))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_MISSING));
    }

    @Test
    void missingChunkSeqHeader_returns400_validation() {
        final String sid = startFresh(1L, 42L, "chunk-no-seq-fixture-001abc");
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/chunks")
                .header(IDEMP_HEADER, "chunk-no-seq-idemp-fixture-1")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(1))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void declaredOversize_returns413() {
        final String sid = startFresh(1L, 42L, "chunk-oversize-fix-001bcd");
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/chunks")
                .header(IDEMP_HEADER, "chunk-oversize-idemp-fix-001b")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "300000")  // > 256 KB
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(1024))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_OVERSIZE));
    }

    @Test
    void unsupportedFormat_returns415() {
        final String sid = startFresh(1L, 42L, "chunk-bad-format-fix-001ccc");
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/chunks")
                .header(IDEMP_HEADER, "chunk-bad-format-idemp-fix-1")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "MP3")  // CLIENT_ALLOWED dışı
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "16")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(16))
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void declaredActualMismatch_returns400() {
        final String sid = startFresh(1L, 42L, "chunk-mismatch-fix-001ddd");
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/chunks")
                .header(IDEMP_HEADER, "chunk-mismatch-idemp-fix-001")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "0")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "100")  // declared 100
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(payload(50))                  // actual 50
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void formatMismatchSession_returns415() {
        // Session WAV ile başlatıldı; chunk PCM16 ile geliyor → 415
        final String sid = startFresh(1L, 42L, "chunk-fmt-mismatch-fix-001");
        chunkRequest(1L, 42L, sid, "chunk-fmt-mismatch-idemp-001",
                0, payload(64), AudioFormat.PCM16, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    // ---------------- Session lookup / owner ----------------

    @Test
    void sessionNotFound_returns404() {
        chunkRequest(1L, 42L, "SES-does-not-exist", "chunk-notfound-idemp-fix-1",
                0, payload(32), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));
    }

    @Test
    void ownerMismatch_returns403() {
        final String sid = startFresh(1L, 42L, "chunk-owner-fix-001eee");
        chunkRequest(99L, 88L, sid, "chunk-owner-idemp-fix-001eee",
                0, payload(32), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN));
    }

    // ---------------- Happy path + sequential + state ----------------

    @Test
    void happyPath_firstChunk_returns200_streamingState() {
        final String sid = startFresh(1L, 42L, "chunk-happy-fix-001fff");
        chunkRequest(1L, 42L, sid, "chunk-happy-idemp-fix-001fff",
                0, payload(128), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChunkAdmissionResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.chunkSeq()).isZero();
                    assertThat(resp.chunkCount()).isEqualTo(1L);
                    assertThat(resp.replayed()).isFalse();
                    assertThat(resp.receivedAtMs()).isPositive();
                });

        // Status: STREAMING + chunkCount=1 + lastChunkSeq=0
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> {
                    assertThat(resp.state()).isEqualTo("STREAMING");
                    assertThat(resp.chunkCount()).isEqualTo(1);
                    assertThat(resp.lastChunkSeq()).isZero();
                });
    }

    @Test
    void sequentialChunks_threeChunks_returns200_chunkCount3() {
        final String sid = startFresh(1L, 42L, "chunk-seq3-fix-001ggg");
        for (long seq = 0; seq < 3; seq++) {
            chunkRequest(1L, 42L, sid, "chunk-seq3-idemp-fix-" + seq + "g",
                    seq, payload(64 + (int) seq), AudioFormat.WAV, 16000, 1)
                    .exchange()
                    .expectStatus().isOk();
        }
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> {
                    assertThat(resp.state()).isEqualTo("STREAMING");
                    assertThat(resp.chunkCount()).isEqualTo(3);
                    assertThat(resp.lastChunkSeq()).isEqualTo(2L);
                });
    }

    // ---------------- Out-of-order / gap ----------------

    @Test
    void gapChunkSeq_returns409_outOfOrder() {
        final String sid = startFresh(1L, 42L, "chunk-gap-fix-001hhh");
        // Skip seq=0; send seq=1 directly → gap
        chunkRequest(1L, 42L, sid, "chunk-gap-idemp-fix-001hhh",
                1, payload(64), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_CHUNK_OUT_OF_ORDER));
    }

    @Test
    void backwardChunkSeq_returns409_outOfOrder() {
        final String sid = startFresh(1L, 42L, "chunk-backward-fix-001iii");
        // First admit seq=0, then try seq=0 again from older state (cant — same seq is replay path)
        // Then admit seq=1 then try seq=0 → goes via "same seq replay" path? No — last accepted is 1
        // Better: admit 0, admit 1, then seq=0 backward → since 0 < 1 and 0 != 1, falls into OutOfOrder
        chunkRequest(1L, 42L, sid, "chunk-backward-idemp-fix-0",
                0, payload(64), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();
        chunkRequest(1L, 42L, sid, "chunk-backward-idemp-fix-1",
                1, payload(80), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();
        // Backward send seq=0 with new idempotency-Key → conflict path (lastAccepted=1, seq=0)
        // Yine OutOfOrder semantik: seq < lastAccepted (0 < 1) → OutOfOrder
        chunkRequest(1L, 42L, sid, "chunk-backward-idemp-fix-bk",
                0, payload(64), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_CHUNK_OUT_OF_ORDER));
    }

    // ---------------- Idempotent replay ----------------

    @Test
    void duplicateChunk_sameIdempotencyAndPayload_returns200_replayed() {
        final String sid = startFresh(1L, 42L, "chunk-replay-fix-001jjj");
        final byte[] body = payload(96);
        final String idemp = "chunk-replay-idemp-fix-001jjj";

        // First call
        chunkRequest(1L, 42L, sid, idemp, 0, body, AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChunkAdmissionResponse.class)
                .value(r -> assertThat(r.replayed()).isFalse());

        // Replay (same key + same seq + same payload)
        chunkRequest(1L, 42L, sid, idemp, 0, body, AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChunkAdmissionResponse.class)
                .value(r -> {
                    assertThat(r.replayed()).isTrue();
                    assertThat(r.chunkCount()).isEqualTo(1L);  // chunkCount artmadı
                    assertThat(r.chunkSeq()).isZero();
                });
    }

    @Test
    void duplicateChunk_sameSeqDifferentIdempotency_returns409_conflict() {
        final String sid = startFresh(1L, 42L, "chunk-conflict-fix-001kkk");
        final byte[] body = payload(96);

        chunkRequest(1L, 42L, sid, "chunk-conflict-idemp-fix-1k1", 0, body,
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();

        // Same chunkSeq=0 but different Idempotency-Key → 409 IDEMPOTENCY_CONFLICT
        chunkRequest(1L, 42L, sid, "chunk-conflict-idemp-fix-1k2", 0, body,
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void duplicateChunk_sameSeqDifferentPayload_returns409_conflict() {
        final String sid = startFresh(1L, 42L, "chunk-payload-conf-fix-1lll");
        final String idemp = "chunk-payload-conf-idemp-fix-1";

        chunkRequest(1L, 42L, sid, idemp, 0, payload(64),
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();

        // Same key + same chunkSeq + DIFFERENT payload → conflict
        chunkRequest(1L, 42L, sid, idemp, 0, payload(96),
                AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    // ---------------- Concurrent finish + admitChunk race (Codex iter-3 P1) -----

    /**
     * Codex {@code 019e8d78} iter-4 sıkılaştırma absorb: {@code Future.get()} ile assertion
     * exception ana thread'e taşınır; finish synchronized sonrası final state deterministik
     * {@code FINISHED} olmalı (synchronized monitor altında finish her zaman terminal state
     * yazar; admitChunk stale snapshot'tan terminal overwrite edemez).
     */
    @Test
    void concurrentFinishAndChunk_finishWinsTerminalDeterministic() throws Exception {
        final String sid = startFresh(1L, 42L, "chunk-race-fix-001-aaa");
        // First chunk to enter STREAMING
        chunkRequest(1L, 42L, sid, "chunk-race-seq0-fix-001abc",
                0, payload(64), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isOk();

        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);

        final java.util.concurrent.Callable<Integer> finishTask = () -> {
            start.await();
            return asUser(1L, 42L)
                    .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                    .header(IDEMP_HEADER, "chunk-race-finish-fix-001a")
                    .exchange()
                    .returnResult(FinishResponse.class)
                    .getStatus().value();
        };

        final java.util.concurrent.Callable<Integer> chunkTask = () -> {
            start.await();
            return chunkRequest(1L, 42L, sid, "chunk-race-seq1-fix-001abc",
                    1, payload(80), AudioFormat.WAV, 16000, 1)
                    .exchange()
                    .returnResult(ChunkAdmissionResponse.class)
                    .getStatus().value();
        };

        final java.util.concurrent.Future<Integer> finishFuture = pool.submit(finishTask);
        final java.util.concurrent.Future<Integer> chunkFuture = pool.submit(chunkTask);
        start.countDown();

        final int finishStatus = finishFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
        final int chunkStatus = chunkFuture.get(15, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Finish her zaman 200 (synchronized monitor altında garantili — synchronized finish
        // ve admitChunk aynı lock üzerinde sırayla çalışır; finish call asla CAS fail etmez)
        assertThat(finishStatus)
                .as("Finish should always succeed (200) under synchronized monitor")
                .isEqualTo(200);
        // Chunk 200 (chunk önce çalıştıysa STREAMING'e yazdı) veya 409 (finish önce çalıştıysa
        // INVALID_TRANSITION — chunk FINISHED state'de reddedildi)
        assertThat(chunkStatus)
                .as("Chunk response is 200 (chunk önce) veya 409 (finish önce → INVALID_TRANSITION)")
                .isIn(200, 409);

        // Terminal state assertion: synchronized fix sonrası final state HER ZAMAN FINISHED
        // (finish call her zaman terminal yazar; admitChunk stale snapshot'tan overwrite edemez)
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> assertThat(resp.state())
                        .as("Terminal state deterministic FINISHED after synchronized finish")
                        .isEqualTo("FINISHED"));
    }

    // ---------------- Invalid state (FINISHED) ----------------

    @Test
    void chunkAfterFinish_returns409_invalidTransition() {
        final String sid = startFresh(1L, 42L, "chunk-after-finish-fix-1mmm");
        // Finish session
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, "chunk-after-finish-fin-fix-1")
                .exchange()
                .expectStatus().isOk();

        // Try chunk after FINISHED → 409 INVALID_TRANSITION
        chunkRequest(1L, 42L, sid, "chunk-after-finish-idemp-1m",
                0, payload(32), AudioFormat.WAV, 16000, 1)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_INVALID_TRANSITION));
    }
}
