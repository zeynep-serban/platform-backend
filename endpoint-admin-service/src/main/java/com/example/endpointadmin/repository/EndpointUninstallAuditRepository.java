package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointUninstallAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * AG-028 Phase 1 — Spring Data JPA repository for
 * {@link EndpointUninstallAudit}.
 *
 * <p>Append-only at the DB layer (V32 trigger). Repository exposes only read
 * + insert (no update/delete derived methods). The history index
 * {@code ix_endpoint_uninstall_audit_tenant_device_reported} powers
 * {@link #findByTenantIdAndDeviceIdOrderByReportedAtDesc}.
 */
public interface EndpointUninstallAuditRepository
        extends JpaRepository<EndpointUninstallAudit, UUID> {

    Optional<EndpointUninstallAudit>
        findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<EndpointUninstallAudit>
        findByCommandId(UUID commandId);

    Page<EndpointUninstallAudit>
        findByTenantIdAndDeviceIdOrderByReportedAtDesc(
                UUID tenantId, UUID deviceId, Pageable pageable);
}
