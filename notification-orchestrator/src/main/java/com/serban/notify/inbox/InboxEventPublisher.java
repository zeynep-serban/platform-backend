package com.serban.notify.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * InboxEventPublisher — emits {@link InboxUpdatedEvent} after subscriber
 * inbox state mutation (Faz 23.3 PR-E.3 + Faz 23.4 PR-E.4 cross-pod).
 *
 * <p><b>Cross-pod default (Faz 23.4 PR-E.4)</b>: emits dirty-flag
 * NOTIFY (orgId+subscriberId only); count recomputed by
 * {@link InboxNotifyListener} post-commit via
 * {@link NotificationInboxRepository#countUnreadBySubscriber}. Avoids
 * stale-snapshot race in concurrent mutations.
 *
 * <p><b>Single-pod fallback</b>: count computed in-process before
 * Spring event publish. Index {@code idx_inbox_unread_badge} (V9 partial
 * index WHERE state = UNREAD) makes count query O(unread rows for
 * subscriber) — typically small.
 *
 * <p><b>Faz 23.4 PR-E.4 cross-pod broadcast</b>: PostgreSQL
 * {@code LISTEN/NOTIFY} pattern. Publisher emits {@code NOTIFY inbox_updated,
 * '<json>'} which all pods (including the originating pod) pick up via
 * {@link InboxNotifyListener}. The listener then re-emits a Spring
 * {@link InboxUpdatedEvent}, which {@link com.serban.notify.api.InboxSseController}
 * forwards to its locally-connected SSE clients. This unifies the cross-pod
 * delivery path: one publish → all pods broadcast to their clients.
 *
 * <p>Why PG LISTEN/NOTIFY (not Redis pub/sub or STOMP+broker)? ADR-0002 §7.1
 * mandates PG-only stateful infrastructure (Mongo/Redis/RabbitMQ YASAK).
 * PG LISTEN/NOTIFY is built-in, transactional (NOTIFY rolls back if
 * transaction does), and adds zero infra dependency. Payload limit 8000
 * bytes per NOTIFY (we send tiny JSON ~80 bytes).
 *
 * <p>Cross-pod activation toggled by {@code notify.inbox.cross-pod-enabled}
 * (default true). When disabled (single-pod test / local dev), publisher
 * falls back to legacy {@link org.springframework.context.ApplicationEventPublisher}
 * path so existing SSE behavior is preserved.
 */
@Component
public class InboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InboxEventPublisher.class);

    /** PG NOTIFY channel name — must match {@link InboxNotifyListener#CHANNEL}. */
    public static final String NOTIFY_CHANNEL = "inbox_updated";

    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private final NotificationInboxRepository inboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notify.inbox.cross-pod-enabled:true}")
    private boolean crossPodEnabled;

    public InboxEventPublisher(
        org.springframework.context.ApplicationEventPublisher applicationEventPublisher,
        NotificationInboxRepository inboxRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.inboxRepository = inboxRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Emit dirty-flag event for {@code (orgId, subscriberId)} so SSE
     * subscribers can be notified to refresh.
     *
     * <p>Codex iter-1 P2.1 absorb: payload no longer carries pre-computed
     * {@code unreadCount}. Stale-count race: two concurrent {@code markAsRead}
     * transactions each see "1 unread" in their own snapshot, both NOTIFY
     * "unreadCount=1", but real post-commit value is 0. Fix: payload =
     * (orgId, subscriberId) only; {@link InboxNotifyListener} recomputes
     * count via {@link NotificationInboxRepository#countUnreadBySubscriber}
     * AFTER NOTIFY arrives (post-commit guaranteed by PG transactional
     * NOTIFY semantics). Result: clients always receive fresh count.
     *
     * <p>Cross-pod path (default): {@code pg_notify('inbox_updated',
     * '<json>')} via JdbcTemplate in caller's transaction. PG transactional
     * NOTIFY: rolls back if caller transaction rolls back ⇒ phantom-event-safe.
     *
     * <p>Single-pod fallback ({@code notify.inbox.cross-pod-enabled=false}):
     * direct {@link org.springframework.context.ApplicationEventPublisher#publishEvent}
     * with pre-computed count — only this JVM's SSE clients see the event.
     * SseController uses {@code @TransactionalEventListener(AFTER_COMMIT,
     * fallbackExecution=true)} so the local event also waits for commit
     * (Codex iter-1 P1.3 absorb). Used for local dev / unit tests where
     * PG LISTEN/NOTIFY infrastructure is overkill.
     *
     * <p>Codex iter-1 P1.2 absorb: {@code DataAccessException} from NOTIFY
     * propagates to caller — caller transaction MUST rollback together with
     * the failed notification (PG marks it rollback-only anyway). Earlier
     * "best-effort + state already committed" semantics was misleading
     * because NOTIFY runs in the caller's transaction.
     */
    public void publishInboxUpdated(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank()) return;
        if (subscriberId == null || subscriberId.isBlank()) return;

        if (crossPodEnabled) {
            publishViaPgNotify(orgId, subscriberId);
        } else {
            // Local fallback path: include count for legacy single-pod
            // listener; SseController @TransactionalEventListener AFTER_COMMIT
            // gates phantom delivery.
            long unreadCount = inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
            log.debug("inbox event (local-only): orgId={} subscriberId={} unreadCount={}",
                orgId, subscriberId, unreadCount);
            applicationEventPublisher.publishEvent(
                new InboxUpdatedEvent(orgId, subscriberId, unreadCount)
            );
        }
    }

    /**
     * Emit {@code pg_notify('inbox_updated', '<json>')} via parameterized
     * SQL (Codex iter-1 P1.4-related: {@code SELECT pg_notify(?, ?)}
     * preferred over hand-built {@code NOTIFY ch, 'literal'} to avoid
     * SQL-literal escaping pitfalls).
     *
     * <p>Payload = {@code {"orgId": ..., "subscriberId": ...}} — count
     * recomputed by listener post-commit (Codex iter-1 P2.1 absorb).
     *
     * <p><b>Why {@code query()} not {@code update()}</b> (Codex iter-3 CI fix):
     * {@code pg_notify} returns {@code void} but PostgreSQL JDBC driver still
     * surfaces a single-row result set. {@code JdbcTemplate.update()} throws
     * {@code DataIntegrityViolationException: A result was returned when none
     * was expected}. {@code query(sql, RowCallbackHandler, args)} consumes
     * the result row (discarded) and runs the underlying {@code execute()}
     * the same way — same parameter binding, same transactional context.
     */
    private void publishViaPgNotify(String orgId, String subscriberId) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "orgId", orgId,
                "subscriberId", subscriberId
            ));
            // Parameterized pg_notify avoids manual SQL literal escaping.
            // RowCallbackHandler lambda is empty — pg_notify return value is
            // void; we just need to consume the result row to satisfy JDBC.
            jdbcTemplate.query(
                "SELECT pg_notify(?, ?)",
                (java.sql.ResultSet rs) -> { /* discard pg_notify void return */ },
                NOTIFY_CHANNEL, payload
            );
            log.debug("inbox NOTIFY: orgId={} subscriberId={} bytes={}",
                orgId, subscriberId, payload.length());
        } catch (JsonProcessingException jpe) {
            // Jackson failure on a 2-string Map is essentially impossible;
            // surface as runtime exception so caller transaction rolls back
            // (consistent with DB-error propagation below).
            throw new IllegalStateException(
                "inbox NOTIFY payload marshal failed (impossible 2-string map)", jpe);
        }
        // Codex iter-1 P1.2 absorb: DataAccessException propagates. NOTIFY
        // runs in caller's transaction; PG marks transaction rollback-only
        // on error. Catching and "best-effort" continuing would leave caller
        // believing state committed when it actually rolled back.
    }
}
