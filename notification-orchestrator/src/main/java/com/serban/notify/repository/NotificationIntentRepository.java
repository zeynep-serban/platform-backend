package com.serban.notify.repository;

import com.serban.notify.domain.NotificationIntent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
