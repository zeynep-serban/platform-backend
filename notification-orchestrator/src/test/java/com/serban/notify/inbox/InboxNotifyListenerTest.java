package com.serban.notify.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InboxNotifyListener unit test (Faz 23.4 PR-E.4).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Notification handler parses JSON payload correctly + emits Spring event</li>
 *   <li>Wrong channel name → skip silently</li>
 *   <li>Empty payload → log + drop (no event)</li>
 *   <li>Malformed JSON → log + drop (no event)</li>
 *   <li>Missing required fields → log + drop (no event)</li>
 *   <li>cross-pod-enabled=false short-circuits initialize</li>
 * </ul>
 *
 * <p>Live PG connection / LISTEN polling integration test deferred to
 * Testcontainers integration suite (separate test class with real PG).
 * This unit test exercises the {@code handleNotification} private method
 * via reflection for fast feedback on payload parsing logic.
 */
class InboxNotifyListenerTest {

    private ApplicationEventPublisher applicationEventPublisher;
    private ObjectMapper objectMapper;
    private NotificationInboxRepository inboxRepository;
    private InboxNotifyListener listener;

    @BeforeEach
    void setUp() {
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        inboxRepository = mock(NotificationInboxRepository.class);
        listener = new InboxNotifyListener(applicationEventPublisher, objectMapper, inboxRepository);
        ReflectionTestUtils.setField(listener, "enabled", true);
    }

    @Test
    void handlerParsesDirtyFlagPayloadRecomputesCountAndEmitsSpringEvent() throws Exception {
        // Codex iter-1 P2.1 absorb: payload is dirty-flag (orgId+subscriberId);
        // listener recomputes count POST-COMMIT via repository (avoids stale
        // snapshot race in concurrent mutations).
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"orgId\":\"default\",\"subscriberId\":\"sub-1\"}"
        );
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(7L);

        invokeHandle(notif);

        verify(inboxRepository).countUnreadBySubscriber("default", "sub-1");
        verify(applicationEventPublisher).publishEvent(argThat((InboxUpdatedEvent ev) ->
            "default".equals(ev.orgId())
                && "sub-1".equals(ev.subscriberId())
                && ev.unreadCount() == 7L  // fresh from repo, not from payload
        ));
    }

    @Test
    void wrongChannelSkipsSilently() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn("some_other_channel");
        when(notif.getParameter()).thenReturn("{}");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void emptyPayloadDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn("");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void nullPayloadDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(null);

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void malformedJsonDropsWithoutThrowing() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn("not-json{");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void missingOrgIdDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"subscriberId\":\"sub-1\",\"unreadCount\":5}"
        );

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void missingSubscriberIdDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"orgId\":\"default\",\"unreadCount\":5}"
        );

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void disabledInitializeSkipsBackgroundThread() {
        ReflectionTestUtils.setField(listener, "enabled", false);

        listener.initialize();

        // Disabled path: no thread, no repository interaction either
        verifyNoInteractions(inboxRepository);
        verifyNoInteractions(applicationEventPublisher);
    }

    private void invokeHandle(PGNotification notif) throws Exception {
        Method m = InboxNotifyListener.class.getDeclaredMethod(
            "handleNotification", PGNotification.class);
        m.setAccessible(true);
        m.invoke(listener, notif);
    }
}
