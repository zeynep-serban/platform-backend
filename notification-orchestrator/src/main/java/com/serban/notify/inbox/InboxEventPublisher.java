package com.serban.notify.inbox;

import com.serban.notify.repository.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * InboxEventPublisher — emits {@link InboxUpdatedEvent} after subscriber
 * inbox state mutation (Faz 23.3 PR-E.3).
 *
 * <p>Recomputes unread count via {@link NotificationInboxRepository#countUnreadBySubscriber}
 * before publishing — listener (SSE controller) pushes fresh count to client.
 *
 * <p>Tradeoff: one extra COUNT query per state mutation. Index
 * {@code idx_inbox_unread_badge} (V9 partial index WHERE state = UNREAD)
 * makes this O(unread rows for subscriber) — typically small.
 */
@Component
public class InboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InboxEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final NotificationInboxRepository inboxRepository;

    public InboxEventPublisher(
        ApplicationEventPublisher applicationEventPublisher,
        NotificationInboxRepository inboxRepository
    ) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.inboxRepository = inboxRepository;
    }

    /**
     * Recompute unread count + emit event. Caller passes (orgId, subscriberId)
     * for the affected subscriber.
     */
    public void publishInboxUpdated(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank()) return;
        if (subscriberId == null || subscriberId.isBlank()) return;

        long unreadCount = inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
        log.debug("inbox event: orgId={} subscriberId={} unreadCount={}",
            orgId, subscriberId, unreadCount);
        applicationEventPublisher.publishEvent(
            new InboxUpdatedEvent(orgId, subscriberId, unreadCount)
        );
    }
}
