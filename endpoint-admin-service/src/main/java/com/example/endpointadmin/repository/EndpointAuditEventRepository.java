package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointAuditEventRepository extends JpaRepository<EndpointAuditEvent, UUID> {

    List<EndpointAuditEvent> findTop50ByTenantIdOrderByOccurredAtDesc(UUID tenantId);

    List<EndpointAuditEvent> findTop50ByDevice_IdOrderByOccurredAtDesc(UUID deviceId);

    /**
     * BE-016 — find the current tail of a tenant's audit hash-chain: the most
     * recent row that already carries an {@code event_hash}. Legacy pre-BE-016
     * rows ({@code event_hash IS NULL}) are skipped, so the first post-deploy
     * hashed row for a tenant naturally becomes its GENESIS. The caller MUST
     * hold the tenant {@code AuditChainLock} before invoking this.
     */
    Optional<EndpointAuditEvent>
        findTop1ByTenantIdAndEventHashIsNotNullOrderByOccurredAtDescIdDesc(UUID tenantId);

    /**
     * BE-016 — verifier feed: every hashed row for a tenant in chain order
     * (oldest → newest). Legacy null-hash rows are excluded.
     */
    List<EndpointAuditEvent>
        findByTenantIdAndEventHashIsNotNullOrderByOccurredAtAscIdAsc(UUID tenantId);

    @Query("""
            select event
            from EndpointAuditEvent event
            left join event.device device
            left join event.command command
            where event.tenantId = :tenantId
              and (:deviceId is null or device.id = :deviceId)
              and (:commandId is null or command.id = :commandId)
              and (:eventType is null or event.eventType = :eventType)
            order by event.occurredAt desc
            """)
    List<EndpointAuditEvent> search(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("commandId") UUID commandId,
            @Param("eventType") String eventType,
            Pageable pageable
    );
}
