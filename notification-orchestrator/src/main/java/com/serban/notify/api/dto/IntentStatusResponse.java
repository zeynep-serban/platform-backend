package com.serban.notify.api.dto;

import com.serban.notify.domain.NotificationIntent;

import java.time.OffsetDateTime;

/**
 * Intent status query response (PR2 minimum scope).
 *
 * <p>Codex 019df9ae non-neg #1 absorb: GET /intents/{intent_id} **org-scoped**;
 * controller header/JWT claim'den org_id alır + repository {@code findByIntentIdAndOrgId}.
 * Cross-tenant status leak prevention.
 *
 * <p>Faz 23.1 PR2: dispatch.enabled=false → status=PENDING kalır (worker PR4'te).
 */
public record IntentStatusResponse(
    String intentId,
    String status,
    String correlationId,
    String topicKey,
    String severity,
    String dataClassification,
    String dispatchReason,  // "DISPATCH_DISABLED" eğer worker yoksa, yoksa null
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static IntentStatusResponse fromEntity(NotificationIntent intent, boolean dispatchEnabled) {
        return new IntentStatusResponse(
            intent.getIntentId(),
            intent.getStatus().name(),
            intent.getCorrelationId(),
            intent.getTopicKey(),
            intent.getSeverity().name(),
            intent.getDataClassification().name(),
            (!dispatchEnabled && intent.getStatus() == NotificationIntent.Status.PENDING)
                ? "DISPATCH_DISABLED" : null,
            intent.getCreatedAt(),
            intent.getUpdatedAt()
        );
    }
}
