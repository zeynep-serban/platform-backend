package com.serban.notify.inbox;

import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * InboxEventPublisher unit test (Faz 23.3 PR-E.3).
 *
 * <p>Verifies recompute-and-publish flow + null/blank guard.
 */
class InboxEventPublisherTest {

    private ApplicationEventPublisher applicationEventPublisher;
    private NotificationInboxRepository inboxRepository;
    private InboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        inboxRepository = mock(NotificationInboxRepository.class);
        publisher = new InboxEventPublisher(applicationEventPublisher, inboxRepository);
    }

    @Test
    void publishesEventWithFreshUnreadCount() {
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(5L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(applicationEventPublisher).publishEvent(argThat((InboxUpdatedEvent ev) ->
            "default".equals(ev.orgId())
                && "sub-1".equals(ev.subscriberId())
                && ev.unreadCount() == 5L
        ));
    }

    @Test
    void zeroUnreadCountStillPublishes() {
        // Caller may have set last UNREAD → READ; event tells SSE to push 0 badge
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(0L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(applicationEventPublisher).publishEvent(any(InboxUpdatedEvent.class));
    }

    @Test
    void nullOrgIdSkipsPublish() {
        publisher.publishInboxUpdated(null, "sub-1");

        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void blankSubscriberIdSkipsPublish() {
        publisher.publishInboxUpdated("default", "");

        verifyNoInteractions(applicationEventPublisher);
    }
}
