package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
    private final SlackWebhookAdapter adapter = new SlackWebhookAdapter(objectMapper);

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

    private static DeliveryTarget target(String url) {
        return new DeliveryTarget("slack", "channel", null, "hash", url, "slack-default");
    }
}
