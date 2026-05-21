package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EndpointAuditEventRepository extends JpaRepository<EndpointAuditEvent, UUID> {

    List<EndpointAuditEvent> findTop50ByTenantIdOrderByOccurredAtDesc(UUID tenantId);

    List<EndpointAuditEvent> findTop50ByDevice_IdOrderByOccurredAtDesc(UUID deviceId);

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
