package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — POST /api/v1/audio-gateway/sessions.
 *
 * <p>ADR-0031 + Codex {@code 019e8c26} iter-3 REVISE absorb:
 * <ul>
 *   <li>Path canonical {@code /api/v1/audio-gateway/sessions}</li>
 *   <li>{@code Idempotency-Key} header (16-128 char opaque)</li>
 *   <li>Atomic create — concurrent same-key duplicate test (P1)</li>
 *   <li>Validation envelope — missing/invalid language (P1)</li>
 *   <li>Replay / Conflict semantics</li>
 *   <li>JWT fail-closed (401/403)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class StartSessionContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";
    private static final String VALID_IDEMP = "fixture-pr-gw-01a-startsession-1";

    @Autowired
    private WebTestClient client;

    private static StartSessionRequest validRequest() {
        return new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 1);
    }

    private WebTestClient withClaims() {
        return client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)));
    }

    @Test
    void noAuth_returns401() {
        client.post()
                .uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void missingIdempotencyKey_returns400() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_MISSING);
                    assertThat(err.retryable()).isFalse();
                });
    }

    @Test
    void shortIdempotencyKey_returns400_invalid() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, "tooshort")  // < 16 char
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_INVALID));
    }

    @Test
    void jwtMissingCompanyIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("userId", 42L)))
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN);
                    assertThat(err.message()).contains("companyId");
                });
    }

    @Test
    void jwtMissingUserIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("companyId", 1L)))
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.message()).contains("userId"));
    }

    @Test
    void unsupportedAudioFormat_returns415() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.MP3, 16000, 1);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-mp3")
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void unsupportedSampleRate_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 22050, 1);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-rate")
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED);
                    assertThat(err.message()).contains("22050");
                });
    }

    @Test
    void stereoChannel_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 2);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-stereo")
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void happyPath_returns201_withSessionAndUrls() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-happy")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).startsWith("SES-");
                    assertThat(resp.correlationId()).isNotNull();
                    assertThat(resp.statusUrl()).contains(resp.sessionId()).endsWith("/status");
                    assertThat(resp.finishUrl()).contains(resp.sessionId()).endsWith("/finish");
                    assertThat(resp.websocketUrl()).contains("/api/v1/audio-gateway/sessions/")
                            .endsWith("/stream");
                    assertThat(resp.chunkUploadUrl()).contains("/api/v1/audio-gateway/sessions/")
                            .endsWith("/chunks");
                    assertThat(resp.sessionStartMs()).isPositive();
                });
    }

    @Test
    void idempotencyReplay_sameValueAndSignature_returns200_withSameSessionId() {
        final String idemp = VALID_IDEMP + "-replay";
        // 1st call → 201
        final String firstId = withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, idemp)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();

        // 2nd call same Idempotency-Key + same signature → 200 with same sessionId (replay)
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, idemp)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isOk()
                .expectBody(StartSessionResponse.class)
                .value(resp -> assertThat(resp.sessionId()).isEqualTo(firstId));
    }

    @Test
    void idempotencyConflict_sameValueDifferentSignature_returns409() {
        final String idemp = VALID_IDEMP + "-conflict";
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, idemp)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated();

        final StartSessionRequest mutated = new StartSessionRequest(
                "MTG-2026-0001", "device-OTHER", "tr", AudioFormat.WAV, 16000, 1);
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, idemp)
                .bodyValue(mutated)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    /**
     * Codex {@code 019e8c26} iter-3 P1 absorb: atomic create — concurrent same-Idempotency-Key
     * iki request aynı session'a düşmeli (Replayed) veya birinden Created + ikinci Replayed
     * dönmeli; iki distinct SES-* asla üretilmemeli.
     */
    @Test
    void atomicCreate_concurrentSameIdempotency_neverProducesTwoSessions() throws Exception {
        final String idemp = VALID_IDEMP + "-concurrent";
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<String> sid1 = new AtomicReference<>();
        final AtomicReference<String> sid2 = new AtomicReference<>();
        final ExecutorService pool = Executors.newFixedThreadPool(2);

        final Runnable task = () -> {
            try {
                start.await();
                final StartSessionResponse resp = withClaims()
                        .post().uri(SESSIONS_PATH)
                        .header(IDEMP_HEADER, idemp)
                        .bodyValue(validRequest())
                        .exchange()
                        .expectStatus().value(s -> assertThat(s).isIn(200, 201))
                        .expectBody(StartSessionResponse.class)
                        .returnResult().getResponseBody();
                if (sid1.compareAndSet(null, resp.sessionId())) {
                    return;
                }
                sid2.set(resp.sessionId());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };

        pool.submit(task);
        pool.submit(task);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(sid1.get()).isNotNull();
        assertThat(sid2.get()).isNotNull();
        assertThat(sid1.get())
                .as("Same Idempotency-Key + same signature must collapse to one session (atomic create)")
                .isEqualTo(sid2.get());
    }

    // ----- Validation envelope (Codex iter-3 P1 absorb) ---------------------

    @Test
    void missingLanguage_returns400_languageRequiredCode() {
        // Bypass record constructor non-null guard — POST raw JSON
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-langmiss")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "meetingId": "MTG-2026-0001",
                          "deviceId": "device-1",
                          "audioFormat": "WAV",
                          "sampleRateHz": 16000,
                          "channels": 1
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_LANGUAGE_REQUIRED));
    }

    @Test
    void invalidLanguageThreeLetter_returns400_validationCode() {
        // "tur" 3-letter ISO 639-2; contract sadece 639-1 (2-letter)
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-tur")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "meetingId": "MTG-2026-0001",
                          "deviceId": "device-1",
                          "language": "tur",
                          "audioFormat": "WAV",
                          "sampleRateHz": 16000,
                          "channels": 1
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code())
                        .isIn(ErrorResponse.CODE_LANGUAGE_REQUIRED, ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void invalidLanguageUnderscore_returns400() {
        // "tr_TR" underscore yerine ISO 639-1 `tr-TR` (hyphen) gerek
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-underscore")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "meetingId": "MTG-2026-0001",
                          "deviceId": "device-1",
                          "language": "tr_TR",
                          "audioFormat": "WAV",
                          "sampleRateHz": 16000,
                          "channels": 1
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code())
                        .isIn(ErrorResponse.CODE_LANGUAGE_REQUIRED, ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void malformedJson_returns400_validationCode() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-malformed")
                .header("Content-Type", "application/json")
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void correlationIdPropagation_echoesHeader() {
        // Düşük-entropy correlation id (gitleaks generic-api-key false-positive guard)
        final String corrId = "test-correlation-fixture-001";

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, VALID_IDEMP + "-corr")
                .header("X-Correlation-Id", corrId)
                .bodyValue(validRequest())
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", corrId);
    }
}
