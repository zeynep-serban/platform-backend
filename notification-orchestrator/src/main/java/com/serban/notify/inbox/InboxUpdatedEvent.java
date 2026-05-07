package com.serban.notify.inbox;

/**
 * InboxUpdatedEvent — Spring ApplicationEvent emitted when a subscriber's
 * inbox state changes (Faz 23.3 PR-E.3).
 *
 * <p>Triggers:
 * <ul>
 *   <li>{@code InAppInboxAdapter.send} — new inbox row inserted</li>
 *   <li>{@code InboxService.markAsRead} — UNREAD → READ (unread count decrements)</li>
 *   <li>{@code InboxService.archive} — *→ ARCHIVED (active list shrinks)</li>
 * </ul>
 *
 * <p>Listener: {@code InboxSseController} broadcasts new unread count to
 * subscriber's connected SSE clients.
 *
 * <p>Cross-pod delivery (Faz 23.4 PR-E.4): events flow through PG
 * LISTEN/NOTIFY pattern by default (cross-pod-enabled=true). Publisher
 * emits {@code pg_notify('inbox_updated', '<json>')} → all pods'
 * {@code InboxNotifyListener} receive → recompute fresh unread count
 * (avoids stale-snapshot race) → re-emit this Spring event locally per
 * pod → SSE controller broadcasts to local clients. Single-pod fallback
 * (cross-pod-enabled=false) preserved for local dev / unit test.
 */
public record InboxUpdatedEvent(
    String orgId,
    String subscriberId,
    long unreadCount
) {}
