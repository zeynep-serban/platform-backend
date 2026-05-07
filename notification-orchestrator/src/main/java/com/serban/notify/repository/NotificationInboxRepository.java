package com.serban.notify.repository;

import com.serban.notify.domain.NotificationInbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * NotificationInbox repository — Faz 23.3 PR-E.1.
 *
 * <p>Query patterns (covered by V9 indexes):
 * <ul>
 *   <li>{@link #findActiveBySubscriber} → idx_inbox_subscriber_active
 *       (state != ARCHIVED listing, newest first)</li>
 *   <li>{@link #countUnreadBySubscriber} → idx_inbox_unread_badge
 *       (state = UNREAD count for badge)</li>
 *   <li>{@link #findByOrgIdAndIdAndSubscriberId} → uq_inbox_org_intent_subscriber
 *       (single row mutation lookup with org+subscriber tenancy guard)</li>
 *   <li>{@link #findByOrgIdAndIntentIdAndSubscriberId} → uq_inbox_org_intent_subscriber
 *       (idempotent fan-out insert lookup)</li>
 * </ul>
 *
 * <p>Tenancy invariant: every read/write filters {@code orgId + subscriberId}
 * to prevent cross-tenant access (defense-in-depth alongside JWT
 * subject claim enforcement at controller layer).
 */
@Repository
public interface NotificationInboxRepository extends JpaRepository<NotificationInbox, Long> {

    /** Lookup for state mutation; tenancy-guarded by org + subscriber. */
    Optional<NotificationInbox> findByOrgIdAndIdAndSubscriberId(
        String orgId, Long id, String subscriberId
    );

    /** Idempotent fan-out lookup (one inbox row per org+intent+subscriber). */
    Optional<NotificationInbox> findByOrgIdAndIntentIdAndSubscriberId(
        String orgId, String intentId, String subscriberId
    );

    /**
     * Subscriber's active inbox (UNREAD + READ; ARCHIVED filtered out).
     *
     * <p>Index hint: idx_inbox_subscriber_active partial index
     * WHERE state != 'ARCHIVED', sorts by created_at DESC.
     */
    @Query("""
        SELECT i FROM NotificationInbox i
        WHERE i.orgId = :orgId
          AND i.subscriberId = :subscriberId
          AND i.state <> com.serban.notify.domain.NotificationInbox.State.ARCHIVED
        ORDER BY i.createdAt DESC
        """)
    Page<NotificationInbox> findActiveBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId,
        Pageable pageable
    );

    /**
     * Unread count for badge display.
     *
     * <p>Index hint: idx_inbox_unread_badge partial index
     * WHERE state = 'UNREAD' — bitmap only count scan.
     */
    @Query("""
        SELECT COUNT(i) FROM NotificationInbox i
        WHERE i.orgId = :orgId
          AND i.subscriberId = :subscriberId
          AND i.state = com.serban.notify.domain.NotificationInbox.State.UNREAD
        """)
    long countUnreadBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Per-row mark-as-read (Faz 23.5 hardening — Codex thread
     * {@code 019e03b5} AGREE iter-1).
     *
     * <p>Atomic, idempotent UNREAD → READ; no-op when already READ or
     * ARCHIVED. The {@code read_at} timestamp is written by the database
     * via {@code NOW()} inside the same statement as the state flip, so
     * single-pod and multi-pod clusters agree on the read clock and the
     * application no longer passes a JVM cutoff/now parameter.
     *
     * <p>Codex iter-1 P1.1 (Faz 23.4 PR-E.1) hold-over:
     * {@code clearAutomatically=true} + {@code flushAutomatically=true}
     * keep the persistence context in sync with the native UPDATE so the
     * service's subsequent {@code findById} re-fetch within the same
     * transaction reflects the post-mutation state.
     *
     * @return rows affected (1 if state mutated UNREAD→READ, 0 otherwise)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE notify.notification_inbox
           SET state = 'READ',
               read_at = NOW()
         WHERE org_id = :orgId
           AND id = :id
           AND subscriber_id = :subscriberId
           AND state = 'UNREAD'
        """, nativeQuery = true)
    int markAsRead(
        @Param("orgId") String orgId,
        @Param("id") Long id,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Archive transition (Faz 23.5 hardening — Codex thread
     * {@code 019e03b5} AGREE iter-1).
     *
     * <p>Idempotent UNREAD/READ → ARCHIVED; no-op when already ARCHIVED.
     * {@code archived_at} is written by the database clock via
     * {@code NOW()} for the same multi-pod consistency reason as
     * {@link #markAsRead}'s {@code read_at}: a single transaction
     * stamps both the state flip and the timeline marker so the
     * archive timeline never drifts from the read clock under HPA.
     *
     * <p>Codex iter-1 P1.1 (Faz 23.4 PR-E.1) hold-over: persistence
     * context flushed/cleared so the service's post-mutation re-fetch
     * returns the ARCHIVED row.
     *
     * @return rows affected (1 if state mutated *→ARCHIVED, 0 otherwise)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE notify.notification_inbox
           SET state = 'ARCHIVED',
               archived_at = NOW()
         WHERE org_id = :orgId
           AND id = :id
           AND subscriber_id = :subscriberId
           AND state <> 'ARCHIVED'
        """, nativeQuery = true)
    int archive(
        @Param("orgId") String orgId,
        @Param("id") Long id,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Bulk mark-all-read with database-canonical cutoff (Faz 23.5 PR1
     * + Faz 23.5 hardening — Codex thread {@code 019e03b5} AGREE
     * iter-1; supersedes the earlier JVM-clock cutoff scheme tracked
     * in Codex thread {@code 019e021f}).
     *
     * <p>Flips every UNREAD row owned by the subscriber whose
     * {@code created_at <= NOW()} to READ in one SQL statement and
     * returns the affected row count for the response body. Idempotent:
     * a follow-up call with no eligible UNREAD rows returns 0.
     *
     * <p><b>Race-safe cutoff</b>: the boundary is captured at WHERE-
     * clause evaluation time inside this single statement (the
     * transaction-start timestamp PostgreSQL returns for {@code NOW()}
     * is identical to the value {@link #currentDatabaseTimestamp()}
     * returns inside the same {@code @Transactional} unit). The
     * {@code read_at} marker is also DB-sourced via {@code NOW()} so
     * the read timeline matches the cutoff predicate exactly. The
     * controller therefore no longer captures
     * {@link OffsetDateTime#now()}; the JVM clock has zero authority
     * over either side of this comparison, which makes the action
     * race-safe across pods regardless of NTP drift.
     *
     * <p>{@code created_at} is also DB-sourced (V14 ALTER + entity
     * {@code insertable=false}); a row that the database stamps after
     * the bulk update commits cannot satisfy the predicate inside the
     * same transaction. Newer rows simply remain UNREAD; the next
     * mark-all-read call sweeps them.
     *
     * @return number of rows that transitioned UNREAD → READ
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE notify.notification_inbox
           SET state = 'READ',
               read_at = NOW()
         WHERE org_id = :orgId
           AND subscriber_id = :subscriberId
           AND state = 'UNREAD'
           AND created_at <= NOW()
        """, nativeQuery = true)
    int markAllAsRead(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Companion to {@link #markAllAsRead(String, String)} — returns the
     * database-canonical cutoff timestamp the bulk UPDATE used so the
     * controller can echo it back in the response shape
     * {@code BulkMarkAllReadResponse}. The two queries run in the same
     * transaction; PostgreSQL's {@code CURRENT_TIMESTAMP} returns the
     * transaction start time, which is identical for both reads.
     *
     * <p>Return type is {@link Instant} because Hibernate's native query
     * type mapper resolves {@code timestamptz} to {@code Instant} (the
     * "zoneless point in time" view); the service layer adapts to
     * {@link OffsetDateTime} for the response wire shape.
     */
    @Query(value = "SELECT CURRENT_TIMESTAMP", nativeQuery = true)
    Instant currentDatabaseTimestamp();

    /**
     * KVKK erasure — bulk delete inbox rows by (org, subscriber).
     *
     * <p>Codex iter-1 P1.2 absorb: existing {@link com.serban.notify.erasure.ErasureService}
     * pipeline only touched intent + delivery + audit; this PR extends it
     * to inbox rows. Hard delete (NOT anonymize) — inbox rows contain
     * subject/body content snapshots which are subscriber-coupled PII;
     * KVKK Art 17 right to erasure requires complete removal.
     *
     * @return rows deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM NotificationInbox i
        WHERE i.orgId = :orgId
          AND i.subscriberId = :subscriberId
        """)
    int deleteByOrgIdAndSubscriberId(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );
}
