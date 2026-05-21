package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Slack incoming webhook adapter (Faz 23.1 PR3 + Faz 23.6 M7 T4.1.1
 * Block Kit migration).
 *
 * <p><b>Payload mode</b> (Codex {@code 019e493f} AGREE):
 * <ul>
 *   <li>{@code text} (default): plain {@code {"text": "..."}} payload,
 *       backward-compatible behavior for installations that didn't
 *       opt into Block Kit yet.</li>
 *   <li>{@code blockkit} (opt-in via {@code NOTIFY_SLACK_PAYLOAD_MODE}):
 *       Block Kit JSON with header severity badge + section body +
 *       context provenance ({@code org_id}, {@code topic_key},
 *       {@code correlation_id}, {@code occurred_at}). Always includes
 *       a {@code text} fallback for notification preview +
 *       accessibility.</li>
 * </ul>
 *
 * <p>Slack Web API path (bot token + {@code chat.postMessage} +
 * threading + interactive actions) is a separate PR (T4.1.3 — Codex
 * iter-1 deferred).
 *
 * <p>Status semantics (unchanged):
 * <ul>
 *   <li>HTTP 2xx → DELIVERED</li>
 *   <li>HTTP 4xx → FAILED (permanent: invalid URL, malformed payload)</li>
 *   <li>HTTP 5xx / timeout / IOException → RETRY (PR4 worker)</li>
 * </ul>
 */
@Component
public class SlackWebhookAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookAdapter.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 10;
    static final String MODE_TEXT = "text";
    static final String MODE_BLOCKKIT = "blockkit";

    private final ObjectMapper objectMapper;
    private final String payloadMode;

    public SlackWebhookAdapter(
        ObjectMapper objectMapper,
        @Value("${notify.adapters.slack.payload-mode:text}") String payloadMode
    ) {
        this.objectMapper = objectMapper;
        String normalized = payloadMode != null
            ? payloadMode.trim().toLowerCase(Locale.ROOT)
            : MODE_TEXT;
        this.payloadMode = (MODE_BLOCKKIT.equals(normalized) ? MODE_BLOCKKIT : MODE_TEXT);
        log.info("SlackWebhookAdapter activated: payloadMode={}", this.payloadMode);
    }

    @Override
    public String channelKey() {
        return "slack";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        String text = message.bodyText() != null && !message.bodyText().isBlank()
            ? message.bodyText()
            : message.subject();
        if (text == null || text.isBlank()) {
            return DeliveryAttemptResult.failed("empty message (no body_text or subject)", null);
        }

        Object payloadObject = MODE_BLOCKKIT.equals(payloadMode)
            ? SlackBlockKitPayloadBuilder.build(target, message, text)
            : Map.of("text", text);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(target.targetRef());
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            String payload = objectMapper.writeValueAsString(payloadObject);
            post.setEntity(new StringEntity(payload, java.nio.charset.StandardCharsets.UTF_8));

            String providerMsgId = "slack-" + UUID.randomUUID();
            return client.execute(post, response -> {
                int code = response.getCode();
                if (code >= 200 && code < 300) {
                    log.info("slack delivered: target=<redacted> code={} msg_id={}",
                        code, providerMsgId);
                    return DeliveryAttemptResult.delivered(providerMsgId);
                } else if (code >= 400 && code < 500) {
                    log.warn("slack permanent FAIL: code={}", code);
                    return DeliveryAttemptResult.failed("HTTP " + code, code);
                } else {
                    log.warn("slack transient RETRY: code={}", code);
                    return DeliveryAttemptResult.retry("HTTP " + code, code);
                }
            });
        } catch (IOException e) {
            log.warn("slack IOException (treating as RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("io: " + e.getClass().getSimpleName(), null);
        }
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }
}
