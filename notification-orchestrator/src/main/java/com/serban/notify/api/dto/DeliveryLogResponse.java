package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.serban.notify.domain.NotificationDelivery;

import java.time.OffsetDateTime;

/**
 * Public delivery log response DTO (Faz 23.5 PR6 — admin / audit endpoints).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: this DTO carries
 * <b>only</b> redacted fields. Raw {@code recipientId},
 * {@code claimToken}, {@code processingLeaseUntil}, raw
 * {@code providerMsgId} and raw {@code failureReason} <b>must never</b>
 * appear here. {@link com.serban.notify.redaction.DeliveryLogRedactor}
 * converts the internal {@link com.serban.notify.repository.projection.DeliveryLogRow}
 * to this response shape.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code providerMsgIdMasked} — provider id with prefix preserved
 *       and suffix kept; middle obscured (e.g. {@code netgsm-***1234}). Empty
 *       string when no provider message id has been recorded.</li>
 *   <li>{@code failureCategory} — deterministic enumeration; raw provider
 *       text never appears.</li>
 *   <li>{@code failureSummaryRedacted} — i18n key style identifier (e.g.
 *       {@code provider.failure.recipient_rejected}) or
 *       {@code PROVIDER_FAILURE_REDACTED} fallback.</li>
 *   <li>{@code activityAt} — search axis used by admin endpoint sort.</li>
 *   <li>{@code recipientHash} — HMAC-SHA256, org-namespaced. Surfacing the
 *       hash is safe (cannot reverse to PII without the pepper).</li>
 * </ul>
 */
public record DeliveryLogResponse(
    @JsonProperty("delivery_id") long deliveryId,
    @JsonProperty("intent_id") String intentId,
    @JsonProperty("topic_key") String topicKey,
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("channel") String channel,
    @JsonProperty("provider") String provider,
    @JsonProperty("provider_msg_id_masked") String providerMsgIdMasked,
    @JsonProperty("recipient_type") NotificationDelivery.RecipientType recipientType,
    @JsonProperty("recipient_hash") String recipientHash,
    @JsonProperty("status") NotificationDelivery.Status status,
    @JsonProperty("attempt_count") int attemptCount,
    @JsonProperty("failure_category") String failureCategory,
    @JsonProperty("failure_summary_redacted") String failureSummaryRedacted,
    @JsonProperty("last_attempt_at") OffsetDateTime lastAttemptAt,
    @JsonProperty("delivered_at") OffsetDateTime deliveredAt,
    @JsonProperty("permanent_failure_at") OffsetDateTime permanentFailureAt,
    @JsonProperty("next_retry_at") OffsetDateTime nextRetryAt,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("updated_at") OffsetDateTime updatedAt,
    @JsonProperty("activity_at") OffsetDateTime activityAt
) {}
