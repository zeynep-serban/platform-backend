package com.serban.notify.repository;

import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.projection.DeliveryLogRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByIntentId(String intentId);

    /**
     * Per-target idempotent lookup (Codex 019df9ef P2 absorb).
     *
     * <p>{@code uq_delivery_intent_channel_recipient (intent_id, channel,
     * recipient_hash)} unique constraint guarantees at most 1 row.
     */
    Optional<NotificationDelivery> findByIntentIdAndChannelAndRecipientHash(
        String intentId, String channel, String recipientHash
    );

    /**
     * Lookup by provider message id (Faz 23.4 PR-F DLR ingest).
     *
     * <p>Used by DLR (Delivery Receipt) callback ingestion: provider posts
     * status update with original message id (e.g. {@code "netgsm-{jobid}"}
     * for NetGSM); we update the existing delivery row.
     *
     * <p>V12 migration adds partial UNIQUE index on
     * {@code (provider_msg_id) WHERE provider_msg_id IS NOT NULL}
     * — globally unique (provider_msg_id zaten provider prefix taşıyor,
     * örn. "netgsm-{jobid}"). {@code findFirst} preserves defensive
     * semantics for pre-V12 data.
     */
    Optional<NotificationDelivery> findFirstByProviderMsgId(String providerMsgId);

    /**
     * Atomic DLR transition (Faz 23.4 PR-F — Codex iter-1 absorb).
     *
     * <p>Native UPDATE with status predicate: only mutates row if current
     * status is ACCEPTED (provider-queued). DLR codes that map to
     * DELIVERED or FAILED can use this; row count = 1 if transition
     * applied, 0 if row not found OR status was not ACCEPTED (terminal
     * conflict / already mutated by concurrent DLR).
     *
     * <p>Multi-pod safe: two pods receiving same DLR concurrently → DB
     * SELECT FOR UPDATE semantics inside UPDATE prevent both from claiming
     * the transition; one gets count=1 (winner), the other count=0 (loser).
     *
     * <p>Field cleanup mirrors V11 trigger but applied at app level for
     * audit clarity (V11 trigger handles defensive case if app forgets).
     *
     * @return rows affected (1 if transition applied, 0 if not ACCEPTED)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE notify.notification_delivery
        SET status = :newStatus,
            delivered_at = CASE WHEN :newStatus = 'DELIVERED' THEN COALESCE(:terminalAt, NOW()) ELSE delivered_at END,
            permanent_failure_at = CASE WHEN :newStatus = 'FAILED' THEN COALESCE(:terminalAt, NOW()) ELSE permanent_failure_at END,
            failure_reason = CASE WHEN :newStatus = 'FAILED' THEN :failureReason ELSE NULL END,
            next_retry_at = NULL,
            processing_lease_until = NULL,
            updated_at = NOW()
        WHERE provider_msg_id = :providerMsgId
          AND status = 'ACCEPTED'
        """, nativeQuery = true)
    int dlrTerminalize(
        @Param("providerMsgId") String providerMsgId,
        @Param("newStatus") String newStatus,
        @Param("terminalAt") java.time.OffsetDateTime terminalAt,
        @Param("failureReason") String failureReason
    );

    @Query("SELECT d FROM NotificationDelivery d WHERE d.status = :status " +
           "AND d.nextRetryAt <= :now")
    List<NotificationDelivery> findDueForRetry(
        @Param("status") NotificationDelivery.Status status,
        @Param("now") OffsetDateTime now,
        Pageable pageable
    );

    long countByStatus(NotificationDelivery.Status status);

    /**
     * Atomic native claim for RetryWorker (Codex 019dfa47 Q1 absorb).
     *
     * <p>Selects RETRY deliveries whose {@code next_retry_at} ≤ now AND lease
     * expired (or null), locks them via {@code SKIP LOCKED}, sets new lease
     * deadline, returns count of claimed rows. Caller fetches with
     * {@link #findByStatusAndProcessingLeaseUntilGreaterThan}.
     */
    @Modifying
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM notify.notification_delivery
            WHERE status = 'RETRY'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= :now
              AND (processing_lease_until IS NULL OR processing_lease_until <= :now)
            ORDER BY next_retry_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE notify.notification_delivery d
        SET processing_lease_until = :leaseUntil,
            claim_token = :claimToken,
            updated_at = :now
        FROM claimed
        WHERE d.id = claimed.id
        """, nativeQuery = true)
    int claimDueForRetry(
        @Param("now") OffsetDateTime now,
        @Param("leaseUntil") OffsetDateTime leaseUntil,
        @Param("claimToken") String claimToken,
        @Param("batchSize") int batchSize
    );

    /**
     * Find deliveries claimed in this exact cycle (Codex 019dfa47 iter-1 P0 absorb).
     * Multi-pod isolation: only this cycle's claims.
     */
    List<NotificationDelivery> findByClaimToken(String claimToken);

    /**
     * Find RETRY deliveries that exceeded max attempts (DLQ candidates).
     */
    @Query("""
        SELECT d FROM NotificationDelivery d
         WHERE d.status = com.serban.notify.domain.NotificationDelivery.Status.RETRY
           AND d.attemptCount >= :maxAttempts
        """)
    List<NotificationDelivery> findExhaustedRetries(
        @Param("maxAttempts") int maxAttempts, Pageable pageable
    );

    /**
     * Intent-scoped delivery log query (Faz 23.5 PR6).
     *
     * <p>JOIN'lı constructor projection {@link DeliveryLogRow}'a; intent
     * org_id boundary {@link com.serban.notify.api.NotifyOrgAccessGuard}
     * tarafından doğrulanır, sonra burada filtre olarak da uygulanır
     * (defense-in-depth: guard atlanırsa boş Page döner).
     *
     * <p>{@code activityAt = COALESCE(permanentFailureAt, deliveredAt,
     * lastAttemptAt, updatedAt, createdAt)} — geç-DLR safe (Codex
     * thread {@code 019e0289}).
     *
     * <p>Sort: {@code id DESC} (intent-scoped scope dar; {@code activityAt}
     * order admin-wide search'in işi).
     */
    @Query(value = """
        SELECT new com.serban.notify.repository.projection.DeliveryLogRow(
            d.id, d.intentId, i.orgId, i.topicKey, i.correlationId,
            d.channel, d.recipientType, d.recipientHash, d.recipientId,
            d.provider, d.providerMsgId, d.status, d.attemptCount,
            d.failureReason, d.claimToken, d.processingLeaseUntil,
            d.lastAttemptAt, d.deliveredAt, d.permanentFailureAt,
            d.nextRetryAt, d.createdAt, d.updatedAt,
            COALESCE(d.permanentFailureAt, d.deliveredAt, d.lastAttemptAt, d.updatedAt, d.createdAt)
        )
        FROM NotificationDelivery d
        JOIN NotificationIntent i ON i.intentId = d.intentId
        WHERE i.intentId = :intentId AND i.orgId = :orgId
        ORDER BY d.id DESC
        """,
        countQuery = """
        SELECT COUNT(d)
        FROM NotificationDelivery d
        JOIN NotificationIntent i ON i.intentId = d.intentId
        WHERE i.intentId = :intentId AND i.orgId = :orgId
        """)
    Page<DeliveryLogRow> findDeliveryLogByIntentIdAndOrgId(
        @Param("intentId") String intentId,
        @Param("orgId") String orgId,
        Pageable pageable
    );

    /**
     * Admin-wide delivery search (Faz 23.5 PR6).
     *
     * <p>Filters: org_id (mandatory), status, channel, provider — null'lar
     * predicate'i devre dışı bırakır. Time window {@code activityAt BETWEEN
     * :from AND :to} zorunlu — admin endpoint default 24h, max 7d
     * range guard controller seviyesinde.
     *
     * <p>Sort: {@code activityAt DESC, id DESC} — geç-DLR'li row'lar üstte;
     * stable secondary sort id desc.
     */
    @Query(value = """
        SELECT new com.serban.notify.repository.projection.DeliveryLogRow(
            d.id, d.intentId, i.orgId, i.topicKey, i.correlationId,
            d.channel, d.recipientType, d.recipientHash, d.recipientId,
            d.provider, d.providerMsgId, d.status, d.attemptCount,
            d.failureReason, d.claimToken, d.processingLeaseUntil,
            d.lastAttemptAt, d.deliveredAt, d.permanentFailureAt,
            d.nextRetryAt, d.createdAt, d.updatedAt,
            COALESCE(d.permanentFailureAt, d.deliveredAt, d.lastAttemptAt, d.updatedAt, d.createdAt)
        )
        FROM NotificationDelivery d
        JOIN NotificationIntent i ON i.intentId = d.intentId
        WHERE i.orgId = :orgId
          AND (:status IS NULL OR d.status = :status)
          AND (:channel IS NULL OR d.channel = :channel)
          AND (:provider IS NULL OR d.provider = :provider)
          AND COALESCE(d.permanentFailureAt, d.deliveredAt, d.lastAttemptAt, d.updatedAt, d.createdAt) BETWEEN :fromTs AND :toTs
        ORDER BY COALESCE(d.permanentFailureAt, d.deliveredAt, d.lastAttemptAt, d.updatedAt, d.createdAt) DESC, d.id DESC
        """,
        countQuery = """
        SELECT COUNT(d)
        FROM NotificationDelivery d
        JOIN NotificationIntent i ON i.intentId = d.intentId
        WHERE i.orgId = :orgId
          AND (:status IS NULL OR d.status = :status)
          AND (:channel IS NULL OR d.channel = :channel)
          AND (:provider IS NULL OR d.provider = :provider)
          AND COALESCE(d.permanentFailureAt, d.deliveredAt, d.lastAttemptAt, d.updatedAt, d.createdAt) BETWEEN :fromTs AND :toTs
        """)
    Page<DeliveryLogRow> searchAdminDeliveryLog(
        @Param("orgId") String orgId,
        @Param("status") NotificationDelivery.Status status,
        @Param("channel") String channel,
        @Param("provider") String provider,
        @Param("fromTs") OffsetDateTime fromTs,
        @Param("toTs") OffsetDateTime toTs,
        Pageable pageable
    );
}
