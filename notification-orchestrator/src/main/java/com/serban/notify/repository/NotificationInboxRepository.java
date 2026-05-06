package com.serban.notify.repository;

import com.serban.notify.domain.NotificationInbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Atomic mark-as-read (idempotent; no-op if already READ/ARCHIVED).
     *
     * <p>Codex iter-1 P1.1 absorb: {@code clearAutomatically=true} +
     * {@code flushAutomatically=true} ensures persistence context stays in
     * sync with bulk JPQL update — caller's subsequent {@code findById}
     * within the same transaction reflects the post-mutation state (avoids
     * stale state response from controller).
     *
     * @return rows affected (1 if state mutated UNREAD→READ, 0 otherwise)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationInbox i
        SET i.state = com.serban.notify.domain.NotificationInbox.State.READ,
            i.readAt = :now
        WHERE i.orgId = :orgId
          AND i.id = :id
          AND i.subscriberId = :subscriberId
          AND i.state = com.serban.notify.domain.NotificationInbox.State.UNREAD
        """)
    int markAsRead(
        @Param("orgId") String orgId,
        @Param("id") Long id,
        @Param("subscriberId") String subscriberId,
        @Param("now") OffsetDateTime now
    );

    /**
     * Archive transition (idempotent; no-op if already ARCHIVED).
     *
     * <p>Codex iter-1 P1.1 absorb: persistence context flushed/cleared so
     * post-mutation re-fetch returns ARCHIVED state.
     *
     * @return rows affected (1 if state mutated *→ARCHIVED, 0 otherwise)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE NotificationInbox i
        SET i.state = com.serban.notify.domain.NotificationInbox.State.ARCHIVED,
            i.archivedAt = :now
        WHERE i.orgId = :orgId
          AND i.id = :id
          AND i.subscriberId = :subscriberId
          AND i.state <> com.serban.notify.domain.NotificationInbox.State.ARCHIVED
        """)
    int archive(
        @Param("orgId") String orgId,
        @Param("id") Long id,
        @Param("subscriberId") String subscriberId,
        @Param("now") OffsetDateTime now
    );

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
