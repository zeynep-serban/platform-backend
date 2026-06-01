package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointServicesSnapshot;
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
 * BE — read/append access for services snapshots (Faz 22.5, AG-039-be).
 * Mirrors AG-038-be {@code EndpointDiagnosticsSnapshotRepository}.
 * All reads JPQL (NEVER native) — schema-qualified by Hibernate from
 * entity mapping, immune to BE-022Q lower(bytea) class regression.
 */
@Repository
public interface EndpointServicesSnapshotRepository
        extends JpaRepository<EndpointServicesSnapshot, UUID>,
        EndpointServicesSnapshotRepositoryCustom {

    Optional<EndpointServicesSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    Optional<EndpointServicesSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    Optional<EndpointServicesSnapshot>
            findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, String payloadHashSha256);

    Page<EndpointServicesSnapshot>
            findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    Page<EndpointServicesSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    @Query("""
            select s
            from EndpointServicesSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointServicesSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);
}
