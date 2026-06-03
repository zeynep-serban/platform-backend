package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.StatusResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — GET /status + POST /finish (lifecycle).
 *
 * <p>ADR-0031 + Codex {@code 019e8c26} iter-2 AGREE + iter-3 REVISE absorb (variable
 * renamed: gitleaks "key" regex false-positive guard).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class SessionLifecycleContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";

    @Autowired
    private WebTestClient client;

    private static StartSessionRequest validRequest() {
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
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();
    }

    // ---------------- Status ----------------

    @Test
    void status_unknownSession_returns404() {
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/SES-does-not-exist/status")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));
    }

    @Test
    void status_otherTenant_returns404() {
        final String sid = startFresh(1L, 42L, "status-other-tenant-fixture-001");

        asUser(2L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void status_started_returnsStartedState() {
        final String sid = startFresh(1L, 42L, "status-started-fixture-001abc");

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.state()).isEqualTo("STARTED");
                    assertThat(resp.chunkCount()).isZero();
                    assertThat(resp.lastChunkSeq()).isZero();
                    assertThat(resp.sessionStartMs()).isPositive();
                });
    }

    // ---------------- Finish ----------------

    @Test
    void finish_missingIdempotencyValue_returns400() {
        final String sid = startFresh(1L, 42L, "finish-missing-fixture-001");

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_MISSING));
    }

    @Test
    void finish_unknownSession_returns404() {
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/SES-not-here/finish")
                .header(IDEMP_HEADER, "finish-unknown-fixture-001abc")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));
    }

    @Test
    void finish_ownerMismatch_returns403() {
        final String sid = startFresh(1L, 42L, "finish-owner-mismatch-fixture-1");

        asUser(99L, 88L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, "finish-other-tenant-fixture-1")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN));
    }

    @Test
    void finish_happyPath_returns200_alreadyFinishedFalse() {
        final String sid = startFresh(1L, 42L, "finish-happy-fixture-aaabbb");
        final String finishIdemp = "finish-happy-idemp-fixture-aa1";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, finishIdemp)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinishResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.finalState()).isEqualTo("FINISHED");
                    assertThat(resp.alreadyFinished()).isFalse();
                    assertThat(resp.finishedAtMs()).isPositive();
                });
    }

    @Test
    void finish_idempotentReplay_sameValue_returns200_alreadyFinishedTrue() {
        final String sid = startFresh(1L, 42L, "finish-replay-fixture-bbbccc");
        final String finishIdemp = "finish-replay-idemp-fixture-b1";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, finishIdemp)
                .exchange()
                .expectStatus().isOk();

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, finishIdemp)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinishResponse.class)
                .value(resp -> {
                    assertThat(resp.alreadyFinished()).isTrue();
                    assertThat(resp.finalState()).isEqualTo("FINISHED");
                });
    }

    @Test
    void finish_idempotencyConflict_differentValueOnFinished_returns409() {
        final String sid = startFresh(1L, 42L, "finish-conflict-fixture-cccddd");
        final String firstIdemp = "finish-conflict-idemp1-fix-c01";
        final String secondIdemp = "finish-conflict-idemp2-fix-c02";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, firstIdemp)
                .exchange()
                .expectStatus().isOk();

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, secondIdemp)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void status_afterFinish_returnsFinishedState() {
        final String sid = startFresh(1L, 42L, "status-after-finish-fixture-1");
        final String finishIdemp = "status-after-finish-idemp-fx1";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_HEADER, finishIdemp)
                .exchange()
                .expectStatus().isOk();

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> assertThat(resp.state()).isEqualTo("FINISHED"));
    }
}
