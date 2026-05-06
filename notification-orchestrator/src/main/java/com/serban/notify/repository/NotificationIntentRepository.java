package com.serban.notify.repository;

import com.serban.notify.domain.NotificationIntent;
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
public interface NotificationIntentRepository extends JpaRepository<NotificationIntent, Long> {

    Optional<NotificationIntent> findByIntentId(String intentId);

    /**
     * Org-scoped intent lookup (Codex 019df9ae non-neg #1 absorb).
     *
     * <p>Cross-tenant status leak prevention — caller's authenticated org_id
     * must match intent's org_id. Returns empty if intent exists but in
     * different org → controller raises {@link com.serban.notify.exception.CrossOrgAccessException}.
     */
    Optional<NotificationIntent> findByIntentIdAndOrgId(String intentId, String orgId);

    @Query("SELECT i FROM NotificationIntent i WHERE i.status = :status " +
           "AND (i.scheduledAt IS NULL OR i.scheduledAt <= :now) " +
           "AND (i.expireAt IS NULL OR i.expireAt > :now)")
    List<NotificationIntent> findDueForProcessing(
        @Param("status") NotificationIntent.Status status,
        @Param("now") OffsetDateTime now,
        Pageable pageable
    );

    long countByStatus(NotificationIntent.Status status);

    /**
     * Atomic native claim for OutboxPoller (Codex 019dfa47 Q1 REVISE absorb).
     *
     * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} ile multi-pod paralel claim
     * safe; CTE içinde claim'lenen intent'lerin id'leri UPDATE'in WHERE
     * clause'una geçer; status PROCESSING'e atomik geçer ve lease setlenir.
     *
     * <p>Returns count of claimed rows. Caller follows with
     * {@link #findClaimedByOwner} to fetch full entity rows.
     *
     * @param now zaman referansı (scheduled_at/expire_at karşılaştırma)
     * @param leaseUntil bu pod'un lease deadline'ı
     * @param owner pod identifier (hostname-pid veya pod IP)
     * @param batchSize tek cycle'da claim edilecek max intent sayısı
     */
    @Modifying
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM notify.notification_intent
            WHERE status = 'PENDING'
              AND (scheduled_at IS NULL OR scheduled_at <= :now)
              AND (expire_at IS NULL OR expire_at > :now)
            ORDER BY COALESCE(scheduled_at, created_at), id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE notify.notification_intent i
        SET status = 'PROCESSING',
            processing_started_at = :now,
            processing_lease_until = :leaseUntil,
            processing_owner = :owner,
            claim_token = :claimToken,
            updated_at = :now
        FROM claimed
        WHERE i.id = claimed.id
        """, nativeQuery = true)
    int claimDueForProcessing(
        @Param("now") OffsetDateTime now,
        @Param("leaseUntil") OffsetDateTime leaseUntil,
        @Param("owner") String owner,
        @Param("claimToken") String claimToken,
        @Param("batchSize") int batchSize
    );

    /**
     * Find intents claimed in this exact cycle by claim_token (Codex 019dfa47
     * iter-1 P0 absorb). Multi-pod isolation: returns ONLY rows this cycle's
     * claim updated.
     */
    List<NotificationIntent> findByClaimToken(String claimToken);

    /**
     * Lease recovery: revert stale-lease PROCESSING intents to PENDING
     * (Codex 019dfa47 Q6 absorb — pod crash recovery).
     *
     * <p>Called by OutboxPoller every cycle BEFORE claim. Stale lease =
     * processing_lease_until &lt;= now. Returns count of recovered rows.
     */
    @Modifying
    @Query(value = """
        UPDATE notify.notification_intent
        SET status = 'PENDING',
            processing_started_at = NULL,
            processing_lease_until = NULL,
            processing_owner = NULL,
            claim_token = NULL,
            updated_at = :now
        WHERE status = 'PROCESSING'
          AND processing_lease_until IS NOT NULL
          AND processing_lease_until <= :now
        """, nativeQuery = true)
    int recoverStaleLeases(@Param("now") OffsetDateTime now);

    /**
     * Find intents whose expire_at passed (poller terminalize to EXPIRED).
     */
    @Query("""
        SELECT i FROM NotificationIntent i
         WHERE i.status IN (com.serban.notify.domain.NotificationIntent.Status.PENDING,
                            com.serban.notify.domain.NotificationIntent.Status.PROCESSING)
           AND i.expireAt IS NOT NULL
           AND i.expireAt <= :now
        """)
    List<NotificationIntent> findExpired(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * Find intents containing a subscriber recipient (KVKK erasure scan).
     *
     * <p>Codex 019dfae5 PR-B Q2 absorb: recipients_snapshot JSONB array of
     * {type:"subscriber",subscriberId:"<id>",...}. PG JSONB containment
     * operator @&gt; matches intents where the snapshot array contains an
     * element with the given subscriber.
     */
    @Query(value = """
        SELECT * FROM notify.notification_intent
         WHERE org_id = :orgId
           AND recipients_snapshot @> CAST(:snapshotMatch AS JSONB)
        """, nativeQuery = true)
    List<NotificationIntent> findIntentsBySubscriberNative(
        @Param("orgId") String orgId,
        @Param("snapshotMatch") String snapshotMatch
    );

    /**
     * Convenience wrapper for {@link #findIntentsBySubscriberNative} —
     * builds JSONB containment match.
     */
    default List<NotificationIntent> findIntentsBySubscriber(String orgId, String subscriberId) {
        // [{"type":"subscriber","subscriberId":"<id>"}] containment match
        String snapshotMatch = "[{\"type\":\"subscriber\",\"subscriberId\":\""
            + subscriberId.replace("\"", "\\\"") + "\"}]";
        return findIntentsBySubscriberNative(orgId, snapshotMatch);
    }
}
