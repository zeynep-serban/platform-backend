package com.serban.notify.api.dto;

import com.serban.notify.domain.NotificationInbox;

import java.time.OffsetDateTime;

/**
 * Inbox item response DTO (Faz 23.3 PR-E.1).
 *
 * <p>Excludes raw subscriber_id / org_id (caller already knows from JWT/header
 * context); inbox-specific surface only.
 */
public record InboxItemResponse(
    Long id,
    String intentId,
    String subject,
    String bodyText,
    String bodyHtml,
    String locale,
    String topicKey,
    String severity,
    String state,
    OffsetDateTime readAt,
    OffsetDateTime archivedAt,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt
) {
    public static InboxItemResponse fromEntity(NotificationInbox e) {
        return new InboxItemResponse(
            e.getId(),
            e.getIntentId(),
            e.getSubject(),
            e.getBodyText(),
            e.getBodyHtml(),
            e.getLocale(),
            e.getTopicKey(),
            e.getSeverity(),
            e.getState().name(),
            e.getReadAt(),
            e.getArchivedAt(),
            e.getCreatedAt(),
            e.getExpiresAt()
        );
    }
}
