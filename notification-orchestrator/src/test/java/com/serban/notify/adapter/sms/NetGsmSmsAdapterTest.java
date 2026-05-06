package com.serban.notify.adapter.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NetGSM SMS adapter test (Faz 23.3.1 — Production MVP geniş scope).
 *
 * <p>Status sınıflandırması:
 * <ul>
 *   <li>HTTP 2xx + provider code "00" → DELIVERED</li>
 *   <li>HTTP 4xx → FAILED (permanent client error)</li>
 *   <li>HTTP 5xx → RETRY (transient)</li>
 *   <li>Provider code 20/30/40/50/70 → FAILED (permanent provider error)</li>
 *   <li>Provider code 60 (insufficient credit) → RETRY</li>
 *   <li>Unknown provider code → RETRY (conservative)</li>
 *   <li>IOException/timeout → RETRY</li>
 * </ul>
 */
class NetGsmSmsAdapterTest {

    @RegisterExtension
    static WireMockExtension netgsm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NetGsmSmsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NetGsmSmsAdapter(objectMapper);
        ReflectionTestUtils.setField(adapter, "apiUrl", netgsm.url("/sms/rest/v2/send"));
        ReflectionTestUtils.setField(adapter, "username", "test-user");
        ReflectionTestUtils.setField(adapter, "password", "test-pass");
        ReflectionTestUtils.setField(adapter, "msgheader", "Notify");
    }

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void delivered2xxWithCode00() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"00\",\"description\":\"OK\",\"jobid\":\"abc-123\"}")));

        DeliveryTarget target = target("+905321111111");
        RenderedMessage msg = new RenderedMessage("Subject", null, "Hello SMS", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).isEqualTo("netgsm-abc-123");

        // Phone "+" stripped, BasicAuth header set, encoding "" for GSM-7
        netgsm.verify(postRequestedFor(urlEqualTo("/sms/rest/v2/send"))
            .withHeader("Content-Type", containing("application/json"))
            .withHeader("Authorization", containing("Basic "))
            .withRequestBody(matchingJsonPath("$.msgheader", containing("Notify")))
            .withRequestBody(matchingJsonPath("$.messages[0].no", containing("905321111111")))
            .withRequestBody(matchingJsonPath("$.messages[0].msg", containing("Hello SMS"))));
    }

    @Test
    void deliveredFallsBackToProviderMsgIdWhenJobidEmpty() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"00\",\"description\":\"OK\"}")));

        DeliveryTarget target = target("+905322222222");
        RenderedMessage msg = new RenderedMessage("S", null, "body", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).startsWith("netgsm-");
    }

    @Test
    void http200MissingProviderCodeRetries() {
        // Codex iter-1 P1 absorb: schema drift safety — only explicit "00"
        // is DELIVERED. Missing/empty code → conservative RETRY (NOT silent
        // success).
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"description\":\"unknown response shape\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.failureReason()).contains("missing");
    }

    @Test
    void http200EmptyBodyRetries() {
        // Codex iter-1 P2 absorb: 2xx empty body → RETRY (provider may have
        // returned malformed response; never silent DELIVERED).
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200).withBody("")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.failureReason()).contains("empty");
    }

    @Test
    void http200MalformedJsonRetries() {
        // Codex iter-1 P2 absorb: malformed JSON → RETRY, not silent
        // DELIVERED. Phone never logged in this path.
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200).withBody("not-a-json{")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.failureReason()).contains("malformed");
    }

    @Test
    void http500EmptyBodyRetries() {
        // 5xx empty body → RETRY (no NPE on null entity).
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(502).withBody("")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isEqualTo(502);
    }

    @Test
    void deliveredTurkishTextSetsEncodingTr() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"00\",\"jobid\":\"tr-1\"}")));

        DeliveryTarget target = target("+905323333333");
        RenderedMessage msg = new RenderedMessage("Konu", null, "Şifre güncellendi", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        netgsm.verify(postRequestedFor(urlEqualTo("/sms/rest/v2/send"))
            .withRequestBody(matchingJsonPath("$.encoding", containing("TR"))));
    }

    // ─── HTTP-level dispatch ─────────────────────────────────────────────

    @Test
    void http500RetryTransient() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(503).withBody("service unavailable")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905324444444"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isEqualTo(503);
    }

    @Test
    void http400PermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(400).withBody("bad request")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905325555555"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.providerResponseCode()).isEqualTo(400);
    }

    // ─── Provider code dispatch ──────────────────────────────────────────

    @Test
    void providerCode50InvalidPhonePermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"50\",\"description\":\"invalid phone format\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905326666666"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("50");
    }

    @Test
    void providerCode70IysOptOutPermanentFailed() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"70\",\"description\":\"IYS opt-out\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905327777777"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("70");
    }

    @Test
    void providerCode60InsufficientCreditRetry() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"60\",\"description\":\"insufficient credit\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905328888888"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        // Code 60 is recoverable (admin tops up credit later)
        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void providerUnknownCodeRetryConservative() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"99\",\"description\":\"unknown\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905329999999"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    // ─── Pre-flight validation ───────────────────────────────────────────

    @Test
    void invalidPhoneFormatFailsBeforeSend() {
        // Codex iter-1 P1/P2 absorb: failureReason MUST NOT contain raw phone.
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("905321234567"),  // missing leading "+"
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("E.164");
        // PII discipline: raw phone must not surface in failureReason
        // (flows into delivery row + audit details).
        assertThat(r.failureReason()).doesNotContain("905321234567");
    }

    @Test
    void emptyMessageFailsBeforeSend() {
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321234567"),
            new RenderedMessage(null, null, null, "tr-TR")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("empty message");
    }

    @Test
    void missingCredentialsFailsBeforeSend() {
        ReflectionTestUtils.setField(adapter, "username", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321234567"),
            new RenderedMessage("S", null, "x", "en-US")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("credentials");
    }

    @Test
    void fallbackToSubjectWhenBodyNull() {
        netgsm.stubFor(post(urlEqualTo("/sms/rest/v2/send"))
            .willReturn(aResponse().withStatus(200)
                .withBody("{\"code\":\"00\",\"jobid\":\"sub-1\"}")));

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321234567"),
            new RenderedMessage("Subject only", null, null, "tr-TR")
        );

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        netgsm.verify(postRequestedFor(urlEqualTo("/sms/rest/v2/send"))
            .withRequestBody(matchingJsonPath("$.messages[0].msg", containing("Subject only"))));
    }

    // ─── Permanent error helper ──────────────────────────────────────────

    @Test
    void isPermanentErrorClassification() {
        assertThat(NetGsmSmsAdapter.isPermanentError("20")).isTrue();
        assertThat(NetGsmSmsAdapter.isPermanentError("30")).isTrue();
        assertThat(NetGsmSmsAdapter.isPermanentError("40")).isTrue();
        assertThat(NetGsmSmsAdapter.isPermanentError("50")).isTrue();
        assertThat(NetGsmSmsAdapter.isPermanentError("70")).isTrue();
        assertThat(NetGsmSmsAdapter.isPermanentError("60")).isFalse();  // credit retry
        assertThat(NetGsmSmsAdapter.isPermanentError("00")).isFalse();
        assertThat(NetGsmSmsAdapter.isPermanentError("99")).isFalse();  // unknown retry
        assertThat(NetGsmSmsAdapter.isPermanentError(null)).isFalse();
    }

    @Test
    void smsChannelKey() {
        assertThat(adapter.channelKey()).isEqualTo("sms");
    }

    private static DeliveryTarget target(String phone) {
        return new DeliveryTarget("sms", "subscriber", "1", "hash-mock", phone, "netgsm-default");
    }
}
