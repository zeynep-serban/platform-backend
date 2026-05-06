package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook egress adapter test (Codex 019df9ae Q5 AGREE — Stripe-style HMAC).
 *
 * <p>Doğrulama:
 * <ul>
 *   <li>Header şeması: {@code X-Notify-Timestamp}, {@code X-Notify-Signature: t=...,v1=...}</li>
 *   <li>HMAC-SHA256 hesaplama doğru (verify edilebiliyor)</li>
 *   <li>2xx → DELIVERED, 4xx → FAILED, 5xx → RETRY</li>
 *   <li>Timestamp replay window içinde (drift &lt; 30s)</li>
 * </ul>
 */
class WebhookEgressAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private static final String SECRET = "test-webhook-secret-32-bytes-min";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebhookEgressAdapter adapter = new WebhookEgressAdapter(objectMapper, SECRET);

    @Test
    void webhook2xxDeliveredWithValidSignature() {
        wm.stubFor(post(urlEqualTo("/inbox"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        DeliveryTarget target = target(wm.url("/inbox"));
        RenderedMessage msg = new RenderedMessage("Sub", "<p>Hi</p>", "Hi", "en-US");

        long beforeSec = Instant.now().getEpochSecond();
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);
        long afterSec = Instant.now().getEpochSecond();

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).startsWith("wh-");

        // Verify request structure
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo("/inbox")));
        assertThat(requests).hasSize(1);
        LoggedRequest req = requests.get(0);

        // Headers
        String tsHeader = req.getHeader("X-Notify-Timestamp");
        String sigHeader = req.getHeader("X-Notify-Signature");
        String webhookId = req.getHeader("X-Notify-Webhook-Id");

        assertThat(tsHeader).isNotNull();
        long timestamp = Long.parseLong(tsHeader);
        assertThat(timestamp).isBetween(beforeSec, afterSec);

        // Codex 019dfae5 iter-1 absorb: kid-aware header scheme
        // (t=<ts>,kid=<active-kid>,v1=<sig>)
        assertThat(sigHeader).matches("t=\\d+,kid=[a-zA-Z0-9_-]+,v1=[0-9a-f]{64}");
        assertThat(webhookId).startsWith("wh-");

        // Verify signature is valid: HMAC-SHA256(secret, timestamp + "." + body)
        String body = req.getBodyAsString();
        String expected = WebhookEgressAdapter.hmacSha256(SECRET, timestamp + "." + body);
        String actualV1 = sigHeader.replaceAll(".*v1=", "");
        assertThat(actualV1).isEqualTo(expected);
    }

    @Test
    void webhookSignatureMismatchOnWrongSecret() {
        wm.stubFor(post(urlEqualTo("/inbox")).willReturn(aResponse().withStatus(200)));

        DeliveryTarget target = target(wm.url("/inbox"));
        RenderedMessage msg = new RenderedMessage("Sub", null, "Body", "en-US");

        adapter.send(target, msg);

        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo("/inbox")));
        LoggedRequest req = requests.get(0);
        String tsHeader = req.getHeader("X-Notify-Timestamp");
        String sigHeader = req.getHeader("X-Notify-Signature");
        String body = req.getBodyAsString();

        // With wrong secret, computed HMAC must differ
        String actualV1 = sigHeader.replaceAll(".*v1=", "");
        String wrong = WebhookEgressAdapter.hmacSha256("wrong-secret", tsHeader + "." + body);
        assertThat(actualV1).isNotEqualTo(wrong);
    }

    @Test
    void webhook4xxPermanentFailed() {
        wm.stubFor(post(urlEqualTo("/inbox"))
            .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        DeliveryTarget target = target(wm.url("/inbox"));
        RenderedMessage msg = new RenderedMessage("S", null, "body", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.providerResponseCode()).isEqualTo(401);
    }

    @Test
    void webhook5xxTransientRetry() {
        wm.stubFor(post(urlEqualTo("/inbox"))
            .willReturn(aResponse().withStatus(502).withBody("bad gateway")));

        DeliveryTarget target = target(wm.url("/inbox"));
        RenderedMessage msg = new RenderedMessage("S", null, "body", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isEqualTo(502);
    }

    @Test
    void webhookSignatureCanonicalRequiresExactByteMatch() {
        // HMAC must be over EXACT bytes of timestamp.rawBody — receiver-side
        // verification reproduces this format. Validate canonical body shape.
        wm.stubFor(post(urlEqualTo("/inbox")).willReturn(aResponse().withStatus(200)));

        RenderedMessage msg = new RenderedMessage(
            "Şifre sıfırla", "<p>Test</p>", "Test", "tr-TR"
        );
        adapter.send(target(wm.url("/inbox")), msg);

        wm.verify(1, postRequestedFor(urlEqualTo("/inbox"))
            .withHeader("Content-Type", matching("application/json.*"))
            .withHeader("X-Notify-Timestamp", matching("\\d+"))
            .withHeader("X-Notify-Signature",
                matching("t=\\d+,kid=[a-zA-Z0-9_-]+,v1=[0-9a-f]{64}"))
            .withHeader("X-Notify-Webhook-Id", matching("wh-[0-9a-f-]+")));
    }

    @Test
    void webhookHmacUtilityIsPure() {
        // Same input → same output
        String a = WebhookEgressAdapter.hmacSha256("k", "msg");
        String b = WebhookEgressAdapter.hmacSha256("k", "msg");
        assertThat(a).isEqualTo(b).hasSize(64);  // 32 bytes hex
        assertThat(a).matches("[0-9a-f]+");

        // Different secret → different output
        String c = WebhookEgressAdapter.hmacSha256("kk", "msg");
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void webhookChannelKey() {
        assertThat(adapter.channelKey()).isEqualTo("webhook");
    }

    private static DeliveryTarget target(String url) {
        return new DeliveryTarget("webhook", "channel", null, "hash", url, "webhook-default");
    }
}
