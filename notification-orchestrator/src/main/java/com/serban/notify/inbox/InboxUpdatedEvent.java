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
 * <p>Listener: {@link InboxSseController} broadcasts new unread count to
 * subscriber's connected SSE clients.
 *
 * <p>Scope (single-pod, intentional): {@code ApplicationEventPublisher} is
 * JVM-local. In a multi-pod deployment, an event raised in pod A is not
 * delivered to pod B's listeners; subscribers connected to pod B miss the
 * push. Acceptable for current notify deployment (HPA min=1 in test, single
 * replica). Cross-pod broadcast (Redis pub/sub or STOMP+message broker)
 * deferred to PR-E.4 / 23.4.
 */
public record InboxUpdatedEvent(
    String orgId,
    String subscriberId,
    long unreadCount
) {}
