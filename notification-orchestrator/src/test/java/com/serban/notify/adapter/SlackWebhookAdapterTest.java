package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slack webhook adapter test (Codex 019df9ae Q6 AGREE — incoming webhook only).
 *
 * <p>Status sınıflandırması:
 * <ul>
 *   <li>2xx → DELIVERED</li>
 *   <li>4xx → FAILED</li>
 *   <li>5xx → RETRY</li>
 * </ul>
 */
class SlackWebhookAdapterTest {

    @RegisterExtension
    static WireMockExtension slack = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    // Faz 23.6 M7 T4.1.1 — constructor signature now takes payloadMode;
    // default 'text' preserves existing test semantics (plain webhook payload).
    private final SlackWebhookAdapter adapter = new SlackWebhookAdapter(objectMapper, "text");

    @Test
    void slack2xxDelivered() {
        slack.stubFor(post(urlEqualTo("/services/T123/B456/xyz"))
            .willReturn(aResponse().withStatus(200).withBody("ok")));

        DeliveryTarget target = target(slack.url("/services/T123/B456/xyz"));
        RenderedMessage msg = new RenderedMessage("Subject", null, "Hello world", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).startsWith("slack-");

        slack.verify(postRequestedFor(urlEqualTo("/services/T123/B456/xyz"))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(equalToJson("{\"text\":\"Hello world\"}")));
    }

    @Test
    void slack4xxPermanentFailed() {
        slack.stubFor(post(urlEqualTo("/services/X"))
            .willReturn(aResponse().withStatus(404).withBody("not_found")));

        DeliveryTarget target = target(slack.url("/services/X"));
        RenderedMessage msg = new RenderedMessage("S", null, "body", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.providerResponseCode()).isEqualTo(404);
    }

    @Test
    void slack5xxTransientRetry() {
        slack.stubFor(post(urlEqualTo("/services/Y"))
            .willReturn(aResponse().withStatus(503).withBody("service_unavailable")));

        DeliveryTarget target = target(slack.url("/services/Y"));
        RenderedMessage msg = new RenderedMessage("S", null, "body", "en-US");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isEqualTo(503);
    }

    @Test
    void slackEmptyBodyFailsBeforeSend() {
        DeliveryTarget target = target("http://localhost:1/never-called");
        RenderedMessage msg = new RenderedMessage(null, null, null, "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("empty message");
    }

    @Test
    void slackFallbackToSubjectWhenBodyTextNull() {
        slack.stubFor(post(urlEqualTo("/svc")).willReturn(aResponse().withStatus(200)));

        DeliveryTarget target = target(slack.url("/svc"));
        RenderedMessage msg = new RenderedMessage("Subject only", null, null, "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        slack.verify(postRequestedFor(urlEqualTo("/svc"))
            .withRequestBody(equalToJson("{\"text\":\"Subject only\"}")));
    }

    @Test
    void slackChannelKey() {
        assertThat(adapter.channelKey()).isEqualTo("slack");
    }

    // ─── Faz 23.6 M7 T4.1.1 — Block Kit mode tests ────────────────────

    @Test
    void blockKitModeSendsBlockKitPayload() throws Exception {
        slack.stubFor(post(urlEqualTo("/blockkit"))
            .willReturn(aResponse().withStatus(200)));

        // Block-kit mode adapter (separate instance — payload-mode='blockkit')
        SlackWebhookAdapter bkAdapter = new SlackWebhookAdapter(objectMapper, "blockkit");
        java.util.Map<String, Object> routingMeta = new java.util.LinkedHashMap<>();
        routingMeta.put("severity", "critical");
        routingMeta.put("org_id", "default");
        routingMeta.put("topic_key", "ops.drift-alarm");
        routingMeta.put("correlation_id", "corr-1");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "hash",
            slack.url("/blockkit"), "slack-default", routingMeta
        );
        RenderedMessage msg = new RenderedMessage("Test subject", null, "Body text", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = bkAdapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        // Verify the request payload had a "blocks" array.
        slack.verify(postRequestedFor(urlEqualTo("/blockkit"))
            .withRequestBody(matchingJsonPath("$.blocks[0].type", equalTo("header")))
            .withRequestBody(matchingJsonPath("$.blocks[1].type", equalTo("section")))
            .withRequestBody(matchingJsonPath("$.blocks[2].type", equalTo("context")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("Body text"))));
    }

    @Test
    void textModeIgnoresBlockKitBuilder() {
        // Default 'text' mode adapter sends plain {"text": "..."} payload.
        slack.stubFor(post(urlEqualTo("/text-mode"))
            .willReturn(aResponse().withStatus(200)));

        java.util.Map<String, Object> routingMeta = java.util.Map.of("severity", "critical");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "hash",
            slack.url("/text-mode"), "slack-default", routingMeta
        );
        RenderedMessage msg = new RenderedMessage("Subject", null, "Plain body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        slack.verify(postRequestedFor(urlEqualTo("/text-mode"))
            .withRequestBody(equalToJson("{\"text\":\"Plain body\"}")));
    }

    @Test
    void unknownModeFallsBackToText() {
        // Defensive: invalid mode value normalizes to 'text' (no crash).
        SlackWebhookAdapter fallbackAdapter = new SlackWebhookAdapter(objectMapper, "INVALID_MODE");
        slack.stubFor(post(urlEqualTo("/fallback"))
            .willReturn(aResponse().withStatus(200)));

        DeliveryTarget target = target(slack.url("/fallback"));
        RenderedMessage msg = new RenderedMessage("Sub", null, "Body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = fallbackAdapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        slack.verify(postRequestedFor(urlEqualTo("/fallback"))
            .withRequestBody(equalToJson("{\"text\":\"Body\"}")));
    }

    private static DeliveryTarget target(String url) {
        return new DeliveryTarget("slack", "channel", null, "hash", url, "slack-default");
    }
}
