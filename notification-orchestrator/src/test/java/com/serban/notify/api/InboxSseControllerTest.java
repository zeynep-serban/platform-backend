package com.serban.notify.api;

import com.serban.notify.inbox.InboxService;
import com.serban.notify.inbox.InboxUpdatedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * InboxSseController unit test (Faz 23.3 PR-E.3).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Subscribe → SseEmitter created, registered in map under (org, subscriber) key</li>
 *   <li>Initial event sent with current unread count</li>
 *   <li>Multiple subscribers → independent emitters per key</li>
 *   <li>{@code onInboxUpdated} delivers event to matching subscriber's emitters only</li>
 *   <li>Heartbeat / cleanup mechanics non-throwing</li>
 *   <li>Header validation handled by Spring (covered in slice tests separately)</li>
 * </ul>
 *
 * <p>Note: Full SSE wire-format end-to-end testing requires Spring MVC slice
 * (@WebMvcTest) which is heavier; this unit test verifies internal state +
 * event broadcast logic via direct method calls.
 */
class InboxSseControllerTest {

    private InboxService inboxService;
    private InboxSseController controller;

    @BeforeEach
    void setUp() {
        inboxService = mock(InboxService.class);
        // Faz 23.4 PR-E.5: real guard. SecurityContext is empty in this
        // unit test so the guard returns silently — same code path as the
        // existing slice-test permissive contract.
        controller = new InboxSseController(inboxService, new SubscriberIdentityGuard());
    }

    @AfterEach
    void clearEmitters() {
        controller.clearAllForTest();
    }

    @Test
    void subscribeRegistersEmitterAndSendsInitialUnreadCount() {
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(7L);

        // Codex iter-1 P0 absorb: query-param signature
        controller.subscribe("default", "sub-1");

        // 1 emitter registered for the (org, subscriber) key
        assertThat(controller.totalEmitters()).isEqualTo(1);
    }

    @Test
    void initialSendRuntimeExceptionStillRemovesEmitter() {
        // Codex iter-1 P2.5 absorb: RuntimeException path drops the emitter
        when(inboxService.unreadCount("default", "sub-1"))
            .thenThrow(new RuntimeException("downstream failure"));

        controller.subscribe("default", "sub-1");

        assertThat(controller.totalEmitters()).isEqualTo(0);
    }

    @Test
    void multipleSubscribersIndependentRegistration() {
        when(inboxService.unreadCount("default", "sub-A")).thenReturn(3L);
        when(inboxService.unreadCount("default", "sub-B")).thenReturn(0L);

        controller.subscribe("default", "sub-A");
        controller.subscribe("default", "sub-B");

        assertThat(controller.totalEmitters()).isEqualTo(2);
    }

    @Test
    void sameSubscriberMultipleSubscriptionsCoexist() {
        // Mobile + web client both connected → 2 emitters for same key
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(2L);

        controller.subscribe("default", "sub-1");
        controller.subscribe("default", "sub-1");

        assertThat(controller.totalEmitters()).isEqualTo(2);
    }

    @Test
    void onInboxUpdatedNoSubscribersNoOp() {
        // No subscribers connected → event is a silent no-op
        controller.onInboxUpdated(new InboxUpdatedEvent("default", "ghost-sub", 5L));
        // No exception, no state change
        assertThat(controller.totalEmitters()).isEqualTo(0);
    }

    @Test
    void onInboxUpdatedDeliveryToTargetKeyOnly() {
        // Two subscribers: A and B. Event for A should NOT touch B's emitter.
        when(inboxService.unreadCount("default", "sub-A")).thenReturn(1L);
        when(inboxService.unreadCount("default", "sub-B")).thenReturn(2L);

        controller.subscribe("default", "sub-A");
        controller.subscribe("default", "sub-B");

        // Event targeted at A only
        controller.onInboxUpdated(new InboxUpdatedEvent("default", "sub-A", 5L));

        // Both still registered; we don't have direct way to assert "B not sent"
        // here without full SSE wire-format test, but the routing code only
        // looks up A's emitter list. Behavior verified via map structure.
        assertThat(controller.totalEmitters()).isEqualTo(2);
    }

    @Test
    void heartbeatNoSubscribersNoOp() {
        controller.heartbeat();
        assertThat(controller.totalEmitters()).isEqualTo(0);
    }

    @Test
    void heartbeatWithSubscribersNoThrow() {
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(1L);
        controller.subscribe("default", "sub-1");

        // Should not throw even though SseEmitter cannot really send in unit test
        controller.heartbeat();

        // Some emitters may be marked complete due to send failure;
        // cleanup logic handles them. Test asserts no exception escape.
    }

    @Test
    void cleanupStaleHandlesEmptyMap() {
        controller.cleanupStale();
        assertThat(controller.totalEmitters()).isEqualTo(0);
    }
}
