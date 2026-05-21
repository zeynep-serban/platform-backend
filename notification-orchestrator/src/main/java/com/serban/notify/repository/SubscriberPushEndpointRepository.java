package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPushEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SubscriberPushEndpoint} (Faz 23.7 M7 T4.2 PR-W1).
 */
@Repository
public interface SubscriberPushEndpointRepository
    extends JpaRepository<SubscriberPushEndpoint, UUID> {

    /**
     * Subscriber'ın aktif endpoint'lerini bul (dispatch için).
     * Soft-deleted (deleted_at IS NOT NULL) hariç.
     */
    @Query("""
        SELECT e FROM SubscriberPushEndpoint e
        WHERE e.orgId = :orgId
          AND e.subscriberId = :subscriberId
          AND e.deletedAt IS NULL
        ORDER BY e.lastSeenAt DESC
        """)
    List<SubscriberPushEndpoint> findActiveBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Idempotent upsert lookup — aynı endpoint_url ikinci subscribe
     * mevcut row döner; subscriber re-register (keys değişebilir).
     */
    Optional<SubscriberPushEndpoint> findByOrgIdAndSubscriberIdAndEndpointUrl(
        String orgId, String subscriberId, String endpointUrl
    );

    /**
     * Soft delete — KVKK Madde 17 right-to-erasure path. ErasureService
     * tarafından subscriber'ın tüm endpoint'lerini deactivate.
     */
    @Modifying
    @Query("""
        UPDATE SubscriberPushEndpoint e
        SET e.deletedAt = :now,
            e.updatedAt = :now
        WHERE e.orgId = :orgId
          AND e.subscriberId = :subscriberId
          AND e.deletedAt IS NULL
        """)
    int softDeleteBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId,
        @Param("now") OffsetDateTime now
    );

    /**
     * Failure counter increment — RFC 8030 410/404 response sonrası.
     * Threshold (örn. 3) aşılırsa caller soft delete tetikler.
     */
    @Modifying
    @Query("""
        UPDATE SubscriberPushEndpoint e
        SET e.failureCount = e.failureCount + 1,
            e.lastFailureAt = :now,
            e.lastFailureReason = :reason,
            e.updatedAt = :now
        WHERE e.endpointId = :endpointId
        """)
    int incrementFailure(
        @Param("endpointId") UUID endpointId,
        @Param("now") OffsetDateTime now,
        @Param("reason") String reason
    );
}
