package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.config.NotifyConfig;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Generic webhook egress adapter — Stripe-style HMAC signature
 * (Codex 019df9ae Q5 AGREE).
 *
 * <p>Signature scheme:
 * <pre>
 * Header: X-Notify-Timestamp: &lt;unix_seconds&gt;
 * Header: X-Notify-Signature: t=&lt;unix_seconds&gt;,v1=&lt;hex&gt;
 * Signed payload: &lt;timestamp&gt;.&lt;raw_body_json&gt;
 * Algorithm: HMAC-SHA256(secret, signed_payload)
 * </pre>
 *
 * <p>Replay protection: receivers verify {@code timestamp} within window
 * (default 300s). Documentation: {@code docs/notify/webhook-egress-signature.md}
 * (gitops PR3 ile birlikte yazılır).
 */
@Component
public class WebhookEgressAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebhookEgressAdapter.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 10;

    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookEgressAdapter(
        ObjectMapper objectMapper,
        @Value("${notify.adapters.webhook.signing-secret:dev-only-secret-not-for-production}")
            String webhookSecret
    ) {
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String channelKey() {
        return "webhook";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        long timestamp = Instant.now().getEpochSecond();
        String providerMsgId = "wh-" + UUID.randomUUID();

        try {
            // Build canonical JSON body
            Map<String, Object> body = Map.of(
                "subject", message.subject() == null ? "" : message.subject(),
                "body_html", message.bodyHtml() == null ? "" : message.bodyHtml(),
                "body_text", message.bodyText() == null ? "" : message.bodyText(),
                "locale", message.locale() == null ? "" : message.locale(),
                "message_id", providerMsgId
            );
            String rawBody = objectMapper.writeValueAsString(body);

            // Sign: timestamp.rawBody
            String signedPayload = timestamp + "." + rawBody;
            String signature = hmacSha256(webhookSecret, signedPayload);

            try (CloseableHttpClient client = newClient()) {
                HttpPost post = new HttpPost(target.targetRef());
                post.setHeader("Content-Type", "application/json; charset=utf-8");
                post.setHeader("X-Notify-Timestamp", String.valueOf(timestamp));
                post.setHeader("X-Notify-Signature", "t=" + timestamp + ",v1=" + signature);
                post.setHeader("X-Notify-Webhook-Id", providerMsgId);
                post.setEntity(new StringEntity(rawBody, StandardCharsets.UTF_8));

                return client.execute(post, response -> {
                    int code = response.getCode();
                    if (code >= 200 && code < 300) {
                        log.info("webhook delivered: target=<redacted> code={} msg_id={}",
                            code, providerMsgId);
                        return DeliveryAttemptResult.delivered(providerMsgId);
                    } else if (code >= 400 && code < 500) {
                        log.warn("webhook permanent FAIL: code={}", code);
                        return DeliveryAttemptResult.failed("HTTP " + code, code);
                    } else {
                        log.warn("webhook transient RETRY: code={}", code);
                        return DeliveryAttemptResult.retry("HTTP " + code, code);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("webhook IOException (treating as RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("io: " + e.getClass().getSimpleName(), null);
        }
    }

    /** Compute HMAC-SHA256 hex (lowercase). Public for receiver-side verification reuse. */
    public static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
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
