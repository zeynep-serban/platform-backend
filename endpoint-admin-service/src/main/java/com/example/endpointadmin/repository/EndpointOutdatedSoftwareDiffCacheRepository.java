package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareDiffCacheRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * BE-024c outdated-software diff summary cache repository (Faz 22.5 P2-A
 * v2-c-pre, Codex 019e88b5 iter-5 AGREE). Same shape as
 * {@code EndpointSoftwareDiffCacheRepository}; read-only JPA in this PR,
 * write path lands in v2-c-pre-2.
 */
@Repository
public interface EndpointOutdatedSoftwareDiffCacheRepository
        extends JpaRepository<EndpointOutdatedSoftwareDiffCacheRow, UUID> {

    Optional<EndpointOutdatedSoftwareDiffCacheRow> findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId);

    long countByTenantId(UUID tenantId);
}
