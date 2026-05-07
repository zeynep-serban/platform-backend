package com.serban.notify.repository.projection;

import com.serban.notify.domain.NotificationDelivery;

import java.time.OffsetDateTime;

/**
 * Internal projection for delivery log search (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: API DTO must never
 * carry raw {@code provider_msg_id}, raw {@code failure_reason},
 * {@code recipient_id}, {@code claim_token} or {@code processing_lease_until}.
 * This row is the constructor-projection target for the repository JPQL —
 * it lives only inside repository / service layers; the controller never
 * sees it. {@code DeliveryLogRedactor} converts to the redacted API DTO.
 *
 * <p>The {@code activityAt} field is the search axis used by admin-wide
 * queries: {@code COALESCE(permanentFailureAt, deliveredAt, lastAttemptAt,
 * updatedAt, createdAt)}. Late DLR terminalization makes {@code created_at}
 * an unsafe sort key — a delivery that fails 6h after intent submission
 * would slip out of a {@code created_at >= now-24h} window.
 *
 * @param deliveryId   numeric primary key
 * @param intentId     intent.intent_id (FK target)
 * @param orgId        org boundary (resolved via JOIN intent on intent_id)
 * @param topicKey     intent.topic_key (operator context for filters)
 * @param correlationId intent.correlation_id (audit linkage)
 * @param channel      delivery channel (email, sms, slack, webhook, in-app)
 * @param recipientType {@link NotificationDelivery.RecipientType}
 * @param recipientHash HMAC-SHA256 hash (org-namespaced; safe to surface)
 * @param recipientId  raw recipient (subscriberId / email / phone) — KVKK
 *                     erasure may null this; redactor MUST drop it
 * @param provider     delivery provider key (smtp, netgsm, slack-webhook,
 *                     webhook-generic, in-app)
 * @param providerMsgId provider message id, often prefixed (netgsm-12345);
 *                      redactor masks to last-4
 * @param status       delivery status enum string
 * @param attemptCount delivery attempt count (provider invocations)
 * @param failureReason raw provider error string — redactor maps to
 *                      category + summaryRedacted; raw NEVER returned
 * @param claimToken   RetryWorker cycle UUID — internal only
 * @param processingLeaseUntil RetryWorker lease deadline — internal only
 * @param lastAttemptAt last provider invocation timestamp
 * @param deliveredAt  terminal success timestamp
 * @param permanentFailureAt terminal failure timestamp
 * @param nextRetryAt  scheduled retry attempt timestamp
 * @param createdAt    row creation
 * @param updatedAt    row last mutation
 * @param activityAt   COALESCE(permanentFailureAt, deliveredAt,
 *                     lastAttemptAt, updatedAt, createdAt)
 */
public record DeliveryLogRow(
    Long deliveryId,
    String intentId,
    String orgId,
    String topicKey,
    String correlationId,
    String channel,
    NotificationDelivery.RecipientType recipientType,
    String recipientHash,
    String recipientId,
    String provider,
    String providerMsgId,
    NotificationDelivery.Status status,
    int attemptCount,
    String failureReason,
    String claimToken,
    OffsetDateTime processingLeaseUntil,
    OffsetDateTime lastAttemptAt,
    OffsetDateTime deliveredAt,
    OffsetDateTime permanentFailureAt,
    OffsetDateTime nextRetryAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime activityAt
) {}
