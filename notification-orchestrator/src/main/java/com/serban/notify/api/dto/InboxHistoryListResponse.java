package com.serban.notify.api.dto;

import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Paged 30-day inbox history response DTO (Faz 23.4 M6a — Codex thread
 * {@code 019e40ec} AGREE iter-2).
 *
 * <p>Distinct from {@link InboxListResponse}: the history surface is a
 * read-only review of every inbox row (UNREAD + READ + ARCHIVED) created
 * within a server-enforced rolling window, so it deliberately carries no
 * {@code unreadCount} badge field — the badge belongs to the active
 * inbox ({@code GET /inbox/me}). Instead it echoes {@code windowStart}
 * (the DB-clock floor the query applied) and {@code windowDays} so the
 * client can label the view ("son N gün") without re-deriving the
 * boundary.
 */
public record InboxHistoryListResponse(
    List<InboxItemResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    OffsetDateTime windowStart,
    int windowDays
) {
    public static InboxHistoryListResponse from(
        Page<com.serban.notify.domain.NotificationInbox> page,
        OffsetDateTime windowStart,
        int windowDays
    ) {
        List<InboxItemResponse> items = page.getContent().stream()
            .map(InboxItemResponse::fromEntity)
            .toList();
        return new InboxHistoryListResponse(
            items,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            windowStart,
            windowDays
        );
    }
}
