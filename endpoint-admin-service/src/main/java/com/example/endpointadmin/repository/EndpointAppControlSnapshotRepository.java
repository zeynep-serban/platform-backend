package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAppControlSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — read/append access for app-control snapshots (Faz 22.5,
 * AG-041-be). Mirrors AG-040-be {@code
 * EndpointStartupExposureSnapshotRepository}. All reads JPQL (NEVER
 * native) — schema-qualified by Hibernate from entity mapping, immune
 * to BE-022Q lower(bytea) class regression.
 */
@Repository
public interface EndpointAppControlSnapshotRepository
        extends JpaRepository<EndpointAppControlSnapshot, UUID>,
        EndpointAppControlSnapshotRepositoryCustom {

    Optional<EndpointAppControlSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    Optional<EndpointAppControlSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    Optional<EndpointAppControlSnapshot>
            findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, String payloadHashSha256);

    Page<EndpointAppControlSnapshot>
            findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    Page<EndpointAppControlSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    @Query("""
            select s
            from EndpointAppControlSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointAppControlSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);
}
