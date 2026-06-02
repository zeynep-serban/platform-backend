package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareDiffCacheRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * BE-024c software diff summary cache repository (Faz 22.5 P2-A v2-c-pre,
 * Codex 019e88b5 iter-5 AGREE). Read-only JPA in this PR; the race-safe
 * native UPSERT writer arrives in v2-c-pre-2 alongside the ingest hooks +
 * backfill (Codex iter-2 UPSERT pattern).
 *
 * <p>One canonical row per {@code (tenantId, deviceId)} per the V27 UNIQUE.
 */
@Repository
public interface EndpointSoftwareDiffCacheRepository
        extends JpaRepository<EndpointSoftwareDiffCacheRow, UUID> {

    Optional<EndpointSoftwareDiffCacheRow> findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId);

    long countByTenantId(UUID tenantId);
}
