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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Slack incoming webhook adapter (Faz 23.1 PR3 — Codex 019df9ae Q6 AGREE).
 *
 * <p>PR3 scope: incoming webhook URL POST only.
 * Slack Web API (bot token + chat.postMessage + Block Kit + threading) is
 * Faz 23.6 (v1) scope.
 *
 * <p>Status semantics:
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

    private final ObjectMapper objectMapper;

    public SlackWebhookAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(target.targetRef());
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            String payload = objectMapper.writeValueAsString(Map.of("text", text));
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
