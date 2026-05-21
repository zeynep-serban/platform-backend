package com.serban.notify.adapter.webpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.repository.SubscriberPushEndpointRepository;
import com.serban.notify.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WebPush channel adapter (Faz 23.7 M7 T4.2 PR-W2.2 — Codex
 * {@code 019e49e7} plan-time AGREE absorb).
 *
 * <p>Channel: {@code push} (product), provider {@code webpush}
 * (technical). Mobile FCM/APNS Faz 22.2 dep, scope dışı.
 *
 * <p>Pattern A (Codex P4 absorb): adapter tek bir endpoint'e tek POST
 * atar; multi-endpoint fan-out {@code DeliveryPlanService} tarafından
 * yapılır (PR-W2.3 scope). Bu adapter {@code target.targetRef} =
 * endpoint_id (UUID string) bekler; repository'den endpoint row
 * lookup ile p256dh + auth_secret çekilir.
 *
 * <p>Status mapping (Codex P6 absorb):
 * <ul>
 *   <li>201 / 204 → DELIVERED (push service accepted; audit:
 *       webpush_outcome=push_service_accepted)</li>
 *   <li>404 / 410 → FAILED + endpoint failure_count++ (RFC 8030
 *       expired); threshold (3) sonrası caller soft delete</li>
 *   <li>413 → FAILED (payload too large — adapter cap'i ile filter)</li>
 *   <li>429 / 5xx → RETRY (PR4 worker backoff)</li>
 *   <li>other 4xx → FAILED</li>
 * </ul>
 *
 * <p>Audit metadata (Codex P5+P6 absorb): endpoint host + response
 * code only. Raw endpoint URL, p256dh, auth_secret ASLA audit'e
 * girmez (KVKK Madde 12 PII boundary).
 */
@Component
@ConditionalOnProperty(
    name = "notify.adapters.webpush.enabled",
    havingValue = "true"
)
public class WebPushAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebPushAdapter.class);

    /** Endpoint failure threshold for soft-delete (Codex iter-3 P6). */
    static final int FAILURE_SOFT_DELETE_THRESHOLD = 3;

    private final WebPushSender sender;
    private final SubscriberPushEndpointRepository endpointRepo;
    private final ObjectMapper objectMapper;

    public WebPushAdapter(
        WebPushSender sender,
        SubscriberPushEndpointRepository endpointRepo,
        ObjectMapper objectMapper
    ) {
        this.sender = sender;
        this.endpointRepo = endpointRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelKey() {
        return "push";
    }

    @Override
    @Transactional
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        // target.targetRef = endpoint_id (UUID string)
        UUID endpointId;
        try {
            endpointId = UUID.fromString(target.targetRef());
        } catch (IllegalArgumentException e) {
            log.warn("webpush invalid target ref (expected endpoint UUID): {}",
                target.targetRef());
            return DeliveryAttemptResult.failed("invalid_target_ref", null);
        }

        var endpointOpt = endpointRepo.findById(endpointId);
        if (endpointOpt.isEmpty() || endpointOpt.get().getDeletedAt() != null) {
            log.warn("webpush endpoint not found or deleted: endpointId={}", endpointId);
            return DeliveryAttemptResult.failed("endpoint_not_found", null);
        }
        var endpoint = endpointOpt.get();

        // Payload: JSON-encoded subject + body + optional metadata.
        // Codex P5: keep PII-minimal; FCM 4KB limit safety cap.
        byte[] payload;
        try {
            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("title", message.subject() != null ? message.subject() : "");
            payloadMap.put("body", message.bodyText() != null ? message.bodyText() : "");
            String json = objectMapper.writeValueAsString(payloadMap);
            payload = json.getBytes(StandardCharsets.UTF_8);
            if (payload.length > sender.getMaxPlaintextBytes()) {
                log.warn("webpush payload exceeds cap: bytes={} cap={}",
                    payload.length, sender.getMaxPlaintextBytes());
                return DeliveryAttemptResult.failed("payload_too_large", null);
            }
        } catch (Exception e) {
            return DeliveryAttemptResult.failed(
                "payload_serialize_error: " + e.getClass().getSimpleName(), null
            );
        }

        // Send
        String providerMsgId = "webpush-" + UUID.randomUUID();
        WebPushSender.SendResult result;
        try {
            result = sender.send(
                endpoint.getEndpointUrl(),
                endpoint.getP256dhKey(),
                endpoint.getAuthSecret(),
                payload,
                sender.getDefaultTtlSeconds()
            );
        } catch (Exception e) {
            // Network / library internal failure — RETRY
            log.warn("webpush send IOException (RETRY): endpointId={} err={}",
                endpointId, e.getClass().getSimpleName());
            return DeliveryAttemptResult.retry(
                "send_error: " + e.getClass().getSimpleName(), null
            );
        }

        int code = result.statusCode();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", "webpush");
        metadata.put("provider_response_code", code);
        metadata.put("encoding", "aes128gcm");

        if (code == 201 || code == 204) {
            log.info("webpush delivered: endpointId={} code={} msg_id={}",
                endpointId, code, providerMsgId);
            metadata.put("delivery_status", "DELIVERED");
            return DeliveryAttemptResult.delivered(providerMsgId);
        }

        if (code == 404 || code == 410) {
            // Subscription expired or unknown — endpoint cleanup.
            // Codex 019e4a3d P2 follow-up absorb: endpoint-level soft-delete
            // (önceki softDeleteBySubscriber subscriber'ın TÜM endpoint'lerini
            // siliyordu — multi-endpoint subscriber Chrome+Firefox+iPad
            // birinde 410 Gone alınca diğer cihazların subscription'ı da
            // kaybediliyordu). softDeleteByEndpointId ile sadece o cihazın
            // endpoint kayıtı soft-delete edilir; diğer cihazlar etkilenmez.
            log.info("webpush endpoint stale (HTTP {}): endpointId={} cleanup",
                code, endpointId);
            int newCount = endpoint.getFailureCount() + 1;
            endpointRepo.incrementFailure(
                endpointId,
                OffsetDateTime.now(),
                code == 410 ? "410_GONE" : "404_NOT_FOUND"
            );
            // Threshold: 1 hit (410 Gone) immediate; 404 needs 3 consecutive.
            if (code == 410 || newCount >= FAILURE_SOFT_DELETE_THRESHOLD) {
                endpointRepo.softDeleteByEndpointId(
                    endpointId,
                    OffsetDateTime.now()
                );
                log.info("webpush endpoint soft-deleted: endpointId={}", endpointId);
            }
            return DeliveryAttemptResult.failed(
                code == 410 ? "subscription_expired" : "endpoint_not_found", code
            );
        }

        if (code == 429 || (code >= 500 && code < 600)) {
            log.warn("webpush transient RETRY: code={}", code);
            return DeliveryAttemptResult.retry("HTTP " + code, code);
        }

        if (code >= 400 && code < 500) {
            log.warn("webpush permanent FAIL: code={}", code);
            return DeliveryAttemptResult.failed("HTTP " + code, code);
        }

        // Unexpected (1xx/3xx) — treat as FAILED to surface anomaly.
        log.warn("webpush unexpected status: code={}", code);
        return DeliveryAttemptResult.failed("unexpected_HTTP_" + code, code);
    }
}
