package com.serban.notify.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Paged inbox listing response DTO (Faz 23.3 PR-E.1).
 *
 * <p>Includes unread badge count alongside the paged items so a single
 * GET /inbox/me response gives the client both the active list and the
 * unread total.
 */
public record InboxListResponse(
    List<InboxItemResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    long unreadCount
) {
    public static InboxListResponse from(
        Page<com.serban.notify.domain.NotificationInbox> page,
        long unreadCount
    ) {
        List<InboxItemResponse> items = page.getContent().stream()
            .map(InboxItemResponse::fromEntity)
            .toList();
        return new InboxListResponse(
            items,
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            unreadCount
        );
    }
}
