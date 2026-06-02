package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract 1.0 — 8 senaryo + canonical ErrorResponse body assert.
 *
 * <p>3-AI mutabakat (Codex 019e879c plan-time + 019e8846 iter-1 absorb +
 * 019e887c v2 clean restart + Mavis msg 78 AGREE):
 * - mockJwt() ile gerçek JWT principal (companyId/userId claim'leri)
 * - Exact status assertion (401/403/415/400/200)
 * - Canonical ErrorResponse body kontrol
 * - companyId/userId backend pattern uyumlu
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class StartSessionContractTest {

    @Autowired
    private WebTestClient client;

    private StartSessionRequest validRequest() {
        return new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                16000, 1, null);
    }

    @Test
    void noAuth_returns401() {
        client.post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void jwtMissingCompanyIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN);
                    assertThat(err.message()).contains("companyId");
                    assertThat(err.retryable()).isFalse();
                });
    }

    @Test
    void jwtMissingUserIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("companyId", 1L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN);
                    assertThat(err.message()).contains("userId");
                });
    }

    @Test
    void unsupportedAudioFormat_returns415() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.MP3,
                16000, 1, null);

        client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void unsupportedSampleRate_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                22050, 1, null);

        client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
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
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                16000, 2, null);

        client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void happyPath_returns200_withSessionAndCorrelationId() {
        client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isOk()
                .expectBody(StartSessionResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).startsWith("SES-");
                    assertThat(resp.correlationId()).isNotNull();
                    assertThat(resp.websocketUrl()).contains(resp.sessionId());
                    assertThat(resp.chunkUploadUrl()).contains(resp.sessionId());
                    assertThat(resp.sessionStartMs()).isPositive();
                });
    }

    @Test
    void correlationIdPropagation_echoesHeader() {
        final String corrId = "c0ffee01-1234-1234-1234-123456789012";

        client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)))
                .post()
                .uri("/api/meeting-audio/sessions")
                .header("X-Correlation-Id", corrId)
                .bodyValue(validRequest())
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", corrId);
    }
}
