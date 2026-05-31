package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
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
 * BE — read/append access for outdated-software snapshots (Faz 22.5,
 * AG-036 ingest). Mirrors the AG-033
 * {@code EndpointDeviceHealthSnapshotRepository} precedent EXACTLY, plus the
 * BE-024 custom native-insert write path
 * ({@link EndpointOutdatedSoftwareSnapshotRepositoryCustom}).
 *
 * <p>Latest-per-device query uses the
 * {@code idx_endpoint_outdated_software_snapshots_tenant_device_time}
 * composite index for index-only scans. The deterministic tiebreaker —
 * {@code collected_at DESC, created_at DESC, id DESC} — matches the index
 * column order so PG can avoid a sort.
 */
@Repository
public interface EndpointOutdatedSoftwareSnapshotRepository
        extends JpaRepository<EndpointOutdatedSoftwareSnapshot, UUID>,
        EndpointOutdatedSoftwareSnapshotRepositoryCustom {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointOutdatedSoftwareSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest snapshot per (tenant, device). Composite index lookup +
     * deterministic ordering.
     */
    Optional<EndpointOutdatedSoftwareSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /** Append-only history per (tenant, device). */
    Page<EndpointOutdatedSoftwareSnapshot> findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Page over all snapshots for a (tenant, device) without an inline
     * {@code OrderBy} — ordering is supplied by the caller-built
     * {@link Pageable} {@code Sort} (the test atomicity helper uses this to
     * count rows by device under a shared container). Composite-index
     * friendly when the caller pins {@code captured/collected_at DESC,
     * created_at DESC, id DESC}.
     */
    Page<EndpointOutdatedSoftwareSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Payload-hash deep-equality dedupe probe — mirrors the BE-022Q lesson
     * (Halildeu/platform-backend#330, commit {@code cfe8a9f3}) and the AG-033
     * device-health precedent.
     *
     * <p>Secondary idempotency layer behind {@link #findBySourceCommandResultId(UUID)}:
     * when the agent re-collects byte-identical outdated-software under a
     * <em>different</em> command-result (so the source_command_result_id
     * probe misses), this returns the most-recent snapshot whose
     * {@code payload_hash_sha256} equals the incoming payload hash, so the
     * service can no-op instead of appending a duplicate row.
     *
     * <h4>Why this query avoids the {@code lower(bytea)} grammar bug</h4>
     *
     * <p>The BE-022Q live bug was a case-insensitive comparison written as
     * {@code lower(s.payloadHashSha256) = lower(:payloadHash)}: with a
     * nullable {@code String} parameter, Hibernate 6 + the PostgreSQL JDBC
     * driver bind the param as {@code bytea} when the value is {@code null},
     * and PG's overload resolver then tries the non-existent
     * {@code lower(bytea)} ({@code SQLGrammarException}) even though the
     * column is {@code VARCHAR(64)}.
     *
     * <p>Two-part fix, type-safe by construction (do NOT regress to
     * {@code lower(bytea)}):
     * <ol>
     *   <li><strong>No {@code lower()} at all.</strong> The hash is always
     *       exactly 64 lowercase hex chars — {@code sha256Hex(...)} emits
     *       {@code HexFormat.of().formatHex(...)} (lowercase) and the DB
     *       CHECK {@code payload_hash_sha256 ~ '^[a-f0-9]{64}$'} (V20)
     *       enforces lowercase storage. A direct {@code =} on the
     *       {@code VARCHAR(64)} column is therefore both correct and
     *       case-exact.</li>
     *   <li><strong>Explicit {@code cast(:payloadHash as string)}.</strong>
     *       Forces Hibernate to emit {@code CAST(? AS varchar)} so the
     *       comparison resolves against {@code varchar} unambiguously and the
     *       driver cannot infer {@code bytea} for the bound parameter.</li>
     * </ol>
     *
     * <p>Returns a {@code List} (capped to one row by the caller via
     * {@code PageRequest.of(0, 1)}) rather than {@code Optional} because more
     * than one snapshot can legitimately carry the same hash (append-only
     * history of byte-identical re-collections) and an {@code Optional}
     * return would raise {@code NonUniqueResultException}; the caller takes
     * the head as the canonical "latest identical".
     */
    @Query("""
            select s
            from EndpointOutdatedSoftwareSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointOutdatedSoftwareSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);

    /**
     * Fleet-wide LATEST snapshot per device for a tenant (Faz 22.5, #1146
     * bulk CSV-export feed). Mirrors the AG-033
     * {@code EndpointDeviceHealthSnapshotRepository#findLatestPerDeviceForTenant}
     * against the outdated-software snapshots, reusing the
     * {@code idx_endpoint_outdated_software_snapshots_tenant_device_time}
     * composite index ordering with the SAME
     * {@code collected_at DESC, created_at DESC, id DESC} tiebreaker as
     * the per-device {@code findFirst...} derived query. The inner window
     * (one row per device, {@code rn = 1}) is capped and used as an
     * {@code IN}-subquery selecting whole snapshot rows; Hibernate maps
     * them by column and the scalar-only mapper never walks the LAZY
     * {@code packages} collection (no N+1). Returns at most {@code limit}
     * entities; the caller fetches {@code cap + 1} and treats an over-cap
     * result as truncated. Native (window function + LIMIT); supported by
     * PostgreSQL and H2 2.x.
     */
    @Query(value = """
            SELECT s.*
            FROM endpoint_outdated_software_snapshots s
            WHERE s.tenant_id = :tenantId
              AND s.id IN (
                SELECT ranked.id
                FROM (
                    SELECT os.id AS id,
                           ROW_NUMBER() OVER (
                               PARTITION BY os.device_id
                               ORDER BY os.collected_at DESC, os.created_at DESC, os.id DESC
                           ) AS rn
                    FROM endpoint_outdated_software_snapshots os
                    WHERE os.tenant_id = :tenantId
                ) ranked
                WHERE ranked.rn = 1
                ORDER BY ranked.id
                LIMIT :limit
            )
            """, nativeQuery = true)
    List<EndpointOutdatedSoftwareSnapshot> findLatestPerDeviceForTenant(
            @Param("tenantId") UUID tenantId,
            @Param("limit") int limit);
}
