package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
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
 * BE — read/append access for diagnostics snapshots (Faz 22.5, AG-038-be).
 * Mirrors the AG-037 V22
 * {@code EndpointHotfixPostureSnapshotRepository} precedent. ALL reads JPQL
 * (NEVER native) per the BE-022Q {@code lower(bytea)} lesson + the #342
 * non-public-schema regression: HQL/JPQL is schema-qualified by Hibernate
 * from the entity mapping, while native unqualified queries fail on the
 * non-public {@code endpoint_admin_service} schema.
 */
@Repository
public interface EndpointDiagnosticsSnapshotRepository
        extends JpaRepository<EndpointDiagnosticsSnapshot, UUID>,
        EndpointDiagnosticsSnapshotRepositoryCustom {

    /** Idempotency probe for the agent SUBMIT-result hook (partial UNIQUE). */
    Optional<EndpointDiagnosticsSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /** Latest snapshot per (tenant, device). */
    Optional<EndpointDiagnosticsSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /** Hash-conflict winner re-lookup for the dual-idempotency same-hash race path. */
    Optional<EndpointDiagnosticsSnapshot>
            findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, String payloadHashSha256);

    /** Append-only history per (tenant, device). */
    Page<EndpointDiagnosticsSnapshot>
            findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    /** Page over all snapshots for a (tenant, device) with caller-supplied Sort. */
    Page<EndpointDiagnosticsSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Payload-hash deep-equality dedupe probe. JPQL with explicit
     * {@code cast(:payloadHash as string)} to avoid the BE-022Q
     * {@code lower(bytea)} grammar bug class. The V23 CHECK
     * {@code payload_hash_sha256 ~ '^[0-9a-f]{64}$'} enforces lowercase
     * storage so a direct {@code =} on {@code VARCHAR(64)} is case-exact.
     */
    @Query("""
            select s
            from EndpointDiagnosticsSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointDiagnosticsSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);

    /**
     * Fleet-wide LATEST snapshot per device for a tenant (future fleet
     * bulk endpoint). JPQL with greatest-per-group {@code NOT EXISTS}
     * tiebreaker matching the composite index column order.
     */
    @Query("""
            select s
            from EndpointDiagnosticsSnapshot s
            where s.tenantId = :tenantId
              and not exists (
                select newer.id
                from EndpointDiagnosticsSnapshot newer
                where newer.tenantId = s.tenantId
                  and newer.deviceId = s.deviceId
                  and (
                    newer.collectedAt > s.collectedAt
                    or (newer.collectedAt = s.collectedAt and newer.createdAt > s.createdAt)
                    or (newer.collectedAt = s.collectedAt and newer.createdAt = s.createdAt
                        and newer.id > s.id)
                  )
              )
            order by s.id
            """)
    List<EndpointDiagnosticsSnapshot> findLatestPerDeviceForTenant(
            @Param("tenantId") UUID tenantId, Pageable pageable);
}
