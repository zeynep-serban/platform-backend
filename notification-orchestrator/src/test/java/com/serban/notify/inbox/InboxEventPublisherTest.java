package com.serban.notify.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InboxEventPublisher unit test (Faz 23.3 PR-E.3 + Faz 23.4 PR-E.4 cross-pod).
 *
 * <p>Verifies recompute-and-publish flow + null/blank guard + cross-pod
 * branching (PG NOTIFY when enabled, ApplicationEventPublisher fallback
 * when disabled).
 */
class InboxEventPublisherTest {

    private ApplicationEventPublisher applicationEventPublisher;
    private NotificationInboxRepository inboxRepository;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private InboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        inboxRepository = mock(NotificationInboxRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();  // real — JSON marshalling tested
        publisher = new InboxEventPublisher(
            applicationEventPublisher, inboxRepository, jdbcTemplate, objectMapper
        );
        // Default: cross-pod enabled (production default)
        ReflectionTestUtils.setField(publisher, "crossPodEnabled", true);
    }

    // ─── Cross-pod path (default) ────────────────────────────────────────

    @Test
    void crossPodEnabledEmitsPgNotifyWithDirtyFlagPayloadOnly() {
        // Codex iter-1 P2.1 absorb: payload is dirty-flag (orgId + subscriberId)
        // ONLY. unreadCount intentionally omitted — listener recomputes
        // post-commit to avoid stale-snapshot race.
        // Codex iter-3 CI fix: SELECT pg_notify(?, ?) returns a row → use
        // jdbcTemplate.query(sql, RowCallbackHandler, args) (NOT update — which
        // throws DataIntegrityViolationException on result).
        publisher.publishInboxUpdated("default", "sub-1");

        // Verify pg_notify was issued via parameterized SELECT with channel +
        // JSON payload containing only orgId and subscriberId (no count)
        verify(jdbcTemplate).query(
            eq("SELECT pg_notify(?, ?)"),
            any(RowCallbackHandler.class),
            eq("inbox_updated"),
            argThat((String json) ->
                json.contains("\"orgId\":\"default\"")
                    && json.contains("\"subscriberId\":\"sub-1\"")
                    && !json.contains("unreadCount")
            )
        );
        // Cross-pod: do NOT directly call applicationEventPublisher;
        // listener will deliver via LISTEN path
        verify(applicationEventPublisher, never()).publishEvent(any());
        // Cross-pod: do NOT pre-compute count (listener does it post-commit)
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void crossPodNotifyDbErrorPropagates() {
        // Codex iter-1 P1.2 absorb: DataAccessException from NOTIFY MUST
        // propagate (NOTIFY runs in caller's transaction; PG marks
        // rollback-only on error). Earlier "best-effort" semantics misled
        // caller into believing state was committed.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("conn lost"))
            .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class),
                anyString(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> publisher.publishInboxUpdated("default", "sub-1")
        );
    }

    // ─── Single-pod fallback ─────────────────────────────────────────────

    @Test
    void crossPodDisabledFallsBackToLocalEventPublisherWithCount() {
        ReflectionTestUtils.setField(publisher, "crossPodEnabled", false);
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(7L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(applicationEventPublisher).publishEvent(argThat((InboxUpdatedEvent ev) ->
            "default".equals(ev.orgId())
                && "sub-1".equals(ev.subscriberId())
                && ev.unreadCount() == 7L
        ));
        // Single-pod: NO PG NOTIFY (uses local Spring event bus)
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void crossPodDisabledZeroCountStillEmits() {
        ReflectionTestUtils.setField(publisher, "crossPodEnabled", false);
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(0L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(applicationEventPublisher).publishEvent(any(InboxUpdatedEvent.class));
    }

    // ─── Validation guards ───────────────────────────────────────────────

    @Test
    void nullOrgIdSkipsPublish() {
        publisher.publishInboxUpdated(null, "sub-1");

        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void blankSubscriberIdSkipsPublish() {
        publisher.publishInboxUpdated("default", "");

        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(jdbcTemplate);
    }
}
