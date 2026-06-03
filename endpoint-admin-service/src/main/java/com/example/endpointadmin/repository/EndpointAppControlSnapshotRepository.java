package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAppControlSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
 *
 * <p>Faz 21.1 PR2b-iv.f — per-device + payload-hash reads migrated
 * from derived {@code findByTenantIdAndDeviceId*} +
 * {@code findByTenantDeviceAndPayloadHash} to explicit {@code @Query}
 * with the canonical effective-org filter in index-friendly form
 * (Codex 019e8dec single-PR AGREE — slice c iter-1 lesson applied):
 *
 * <pre>
 *   WHERE s.tenant_id = :orgId
 *     AND (s.org_id = :orgId OR s.org_id IS NULL)
 *     AND s.device_id = :deviceId
 * </pre>
 *
 * <p>V30 {@code CHECK (org_id IS NULL OR org_id = tenant_id)} guarantees
 * semantic equivalence with the canonical effective-org form; the
 * explicit {@code tenant_id = :orgId} predicate keeps the composite
 * index usable. V26 makes {@code tenant_id NOT NULL}, so the legacy
 * NULL fallback survives.
 *
 * <p>The first method, {@link #findBySourceCommandResultId(UUID)}, is
 * NOT migrated — partial UNIQUE on {@code source_command_result_id}
 * is per-result by itself.
 *
 * <p>V26 {@code (tenant_id, device_id, payload_hash_sha256)} UNIQUE
 * means a given (tenant, device, hash) tuple has at most one row, so
 * the payload-hash probe is naturally single-row; the wrapper
 * {@code findFirstByOrgAndDevice...} pattern (List + PageRequest cap)
 * still chosen for parity with slice d-B safety (no
 * NonUniqueResultException class risk).
 */
@Repository
public interface EndpointAppControlSnapshotRepository
        extends JpaRepository<EndpointAppControlSnapshot, UUID>,
        EndpointAppControlSnapshotRepositoryCustom {

    /**
     * Idempotency probe for the agent SUBMIT-result hook. NOT migrated —
     * partial UNIQUE on {@code source_command_result_id} is per-result
     * by itself.
     */
    Optional<EndpointAppControlSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Canonical PR2b-iv.f read — top-N (or full history) per (org,
     * device) sorted by the index tail.
     */
    @Query("""
            select s
            from EndpointAppControlSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointAppControlSnapshot>
            findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);

    /**
     * Portable LIMIT 1 default wrapper around the latest-then-history
     * canonical read (Codex 019e8dec AGREE — same idiom as slice d-A).
     */
    default Optional<EndpointAppControlSnapshot>
            findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID orgId, UUID deviceId) {
        List<EndpointAppControlSnapshot> head =
                findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgId, deviceId, PageRequest.of(0, 1));
        return head.isEmpty() ? Optional.empty() : Optional.of(head.get(0));
    }

    /**
     * Canonical PR2b-iv.f read — paged history per (org, device);
     * ordering supplied by caller Pageable Sort. {@code countQuery}
     * sibling computes total over the same OR-fallback predicate.
     */
    @Query(value = """
            select s
            from EndpointAppControlSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            """,
            countQuery = """
            select count(s)
            from EndpointAppControlSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            """)
    Page<EndpointAppControlSnapshot>
            findVisibleToOrgAndDeviceId(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);

    /**
     * Canonical PR2b-iv.f payload-hash dedupe probe (Codex 019e8dec
     * AGREE single-PR). Mirrors slice d-B method 5 shape: BE-022Q
     * {@code cast(:payloadHash as string)} retained verbatim, outer
     * effective-org filter added.
     *
     * <p>V26 {@code (tenant_id, device_id, payload_hash_sha256)} UNIQUE
     * implies one row at most for a given (tenant, device, hash), but
     * the List + Pageable cap pattern is preserved for parity with
     * slice d-B and to avoid the {@code @Query Optional
     * NonUniqueResultException} class entirely.
     */
    @Query("""
            select s
            from EndpointAppControlSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointAppControlSnapshot> findByOrgAndDeviceAndPayloadHash(
            @Param("orgId") UUID orgId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);

    /**
     * Portable LIMIT 1 default wrapper for the payload-hash probe.
     * Used by the AppControl ingest dedupe winner-selection path
     * (Codex 019e8dec — replaces the prior derived
     * {@code findFirstByTenantIdAndDeviceIdAndPayloadHashSha256...Optional}
     * which would risk {@code NonUniqueResultException} under append-only
     * row duplication; V26 UNIQUE prevents that here but the wrapper
     * preserves slice d-B idiom).
     */
    default Optional<EndpointAppControlSnapshot>
            findFirstByOrgAndDeviceAndPayloadHash(
                    UUID orgId, UUID deviceId, String payloadHash) {
        List<EndpointAppControlSnapshot> head =
                findByOrgAndDeviceAndPayloadHash(
                        orgId, deviceId, payloadHash, PageRequest.of(0, 1));
        return head.isEmpty() ? Optional.empty() : Optional.of(head.get(0));
    }
}
