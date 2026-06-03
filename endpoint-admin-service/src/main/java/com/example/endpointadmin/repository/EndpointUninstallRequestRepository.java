package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AG-028 Phase 1 — Spring Data JPA repository for
 * {@link EndpointUninstallRequest}.
 *
 * <p>Tenant-scoped finders. The partial unique index
 * {@code uq_endpoint_uninstall_one_inflight} guarantees at most one open
 * request per {@code (tenant, device, catalog_item)}; second concurrent
 * propose hits the DB constraint that the service maps to 409 CONFLICT.
 * The partial unique index {@code uq_endpoint_uninstall_idempotency}
 * powers replay semantics on {@code (tenant, idempotency_key)}.
 */
public interface EndpointUninstallRequestRepository
        extends JpaRepository<EndpointUninstallRequest, UUID> {

    Optional<EndpointUninstallRequest>
        findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Idempotency replay lookup — used by the service layer BEFORE running
     * the catalog/provenance/in-flight guards. If the same
     * {@code (tenant, idempotency_key)} pair already has a request, the
     * service returns that existing request id instead of creating a new one
     * (Codex iter-1 must-fix #1 absorb).
     */
    Optional<EndpointUninstallRequest>
        findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Open-request guard (read-path) — used by the service layer to short-
     * circuit on a known in-flight before relying on the DB partial unique
     * index. Returns the open row if one exists for the given
     * {@code (tenant, device, catalog)} tuple.
     */
    @Query("""
            SELECT r FROM EndpointUninstallRequest r
            WHERE r.tenantId = :tenantId
              AND r.deviceId = :deviceId
              AND r.catalogItemId = :catalogItemId
              AND r.state <> com.example.endpointadmin.model.UninstallRequestState.TERMINAL
            """)
    Optional<EndpointUninstallRequest> findOpenForDeviceAndCatalog(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("catalogItemId") UUID catalogItemId);

    Page<EndpointUninstallRequest>
        findByTenantIdAndDeviceIdOrderByCreatedAtDesc(
                UUID tenantId, UUID deviceId, Pageable pageable);

    List<EndpointUninstallRequest>
        findByTenantIdAndState(UUID tenantId, UninstallRequestState state);
}
