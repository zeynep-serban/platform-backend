package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
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
 * BE — read access for device-health snapshots (Faz 22.5, AG-033
 * ingest). Mirrors the BE-022
 * {@code EndpointHardwareInventorySnapshotRepository} precedent EXACTLY.
 *
 * <p>Latest-per-device query uses the
 * {@code idx_endpoint_device_health_snapshots_tenant_device_time}
 * composite index for index-only scans. The deterministic tiebreaker —
 * {@code collected_at DESC, created_at DESC, id DESC} — matches the
 * index column order so PG can avoid a sort.
 */
@Repository
public interface EndpointDeviceHealthSnapshotRepository
        extends JpaRepository<EndpointDeviceHealthSnapshot, UUID> {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointDeviceHealthSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest snapshot per (tenant, device). Composite index lookup +
     * deterministic ordering.
     */
    Optional<EndpointDeviceHealthSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /** Append-only history per (tenant, device). */
    Page<EndpointDeviceHealthSnapshot> findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Payload-hash deep-equality dedupe probe — mirrors the just-merged
     * BE-022Q lesson (Halildeu/platform-backend#327, commit
     * {@code cfe8a9f3}).
     *
     * <p>Secondary idempotency layer behind {@link #findBySourceCommandResultId(UUID)}:
     * when the agent re-collects byte-identical device-health under a
     * <em>different</em> command-result (so the source_command_result_id
     * probe misses), this returns the most-recent snapshot whose
     * {@code payload_hash_sha256} equals the incoming payload hash, so the
     * service can no-op instead of appending a duplicate row.
     *
     * <h4>Why this query avoids the {@code lower(bytea)} grammar bug</h4>
     *
     * The BE-022Q live testai bug (commit {@code 9a2831d5}) was a
     * case-insensitive comparison written as
     * {@code lower(s.payloadHashSha256) = lower(:payloadHash)}: with a
     * nullable {@code String} parameter, Hibernate 6 + the PostgreSQL
     * JDBC driver bind the param as {@code bytea} when the value is
     * {@code null}, and PG's overload resolver then tries the
     * non-existent {@code lower(bytea)} ({@code SQLGrammarException}) even
     * though the column is {@code VARCHAR(64)}.
     *
     * <p>Two-part fix, type-safe by construction (do NOT regress to
     * {@code lower(bytea)}):
     * <ol>
     *   <li><strong>No {@code lower()} at all.</strong> The hash is always
     *       exactly 64 lowercase hex chars — {@code sha256Hex(...)} emits
     *       {@code HexFormat.of().formatHex(...)} (lowercase) and the DB
     *       CHECK {@code payload_hash_sha256 ~ '^[a-f0-9]{64}$'} (V17)
     *       enforces lowercase storage. A direct {@code =} on the
     *       {@code VARCHAR(64)} column is therefore both correct and
     *       case-exact; case folding is dead weight that only
     *       reintroduces the {@code lower()} overload-resolution risk.</li>
     *   <li><strong>Explicit {@code cast(:payloadHash as string)}.</strong>
     *       Forces Hibernate to emit {@code CAST(? AS varchar)} so the
     *       comparison resolves against {@code varchar} unambiguously and
     *       the driver cannot infer {@code bytea} for the bound parameter
     *       regardless of the surrounding binding context.</li>
     * </ol>
     *
     * <p>The {@code order by collected_at desc, created_at desc, id desc}
     * shape reuses the
     * {@code idx_endpoint_device_health_snapshots_tenant_device_time}
     * composite index. Returns a {@code List} (capped to one row by the
     * caller via {@code PageRequest.of(0, 1)}) rather than {@code Optional}
     * because more than one snapshot can legitimately carry the same hash
     * (append-only history of byte-identical re-collections) and an
     * {@code Optional} return would raise {@code NonUniqueResultException};
     * the caller takes the head as the canonical "latest identical".
     */
    @Query("""
            select s
            from EndpointDeviceHealthSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointDeviceHealthSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);

    /**
     * Fleet-wide LATEST snapshot per device for a tenant (Faz 22.5, #1146
     * bulk CSV-export feed). The caller passes {@code PageRequest.of(0,
     * cap + 1)} and treats an over-cap result as truncated (see
     * {@code BulkLatestSnapshots}).
     *
     * <p><strong>JPQL, not native</strong> (live-bug fix): the live testai
     * database keeps these tables in a non-{@code public} schema
     * ({@code endpoint_admin_service}); a native query with an unqualified
     * {@code FROM endpoint_device_health_snapshots} fails with
     * {@code relation ... does not exist} because the connection
     * search_path does not include that schema (a Testcontainers-PG test
     * pinned to {@code public} could not catch it). HQL/JPQL is qualified
     * by Hibernate from the entity's mapped schema for EVERY dialect — the
     * same reason the per-device {@code findFirst...} queries already work
     * on live — so entity queries are schema-safe by construction.
     *
     * <p>"Latest per device" is expressed as the greatest-per-group
     * {@code NOT EXISTS} (no strictly-newer snapshot exists for the same
     * (tenant, device)), with the SAME deterministic tiebreaker as the
     * per-device derived query: lexicographic {@code (collected_at,
     * created_at, id)} DESC (all three columns are {@code NOT NULL}, so no
     * null-ordering ambiguity). The correlated probe rides the
     * {@code idx_endpoint_device_health_snapshots_tenant_device_time}
     * composite index. {@code select s} loads whole entities; the
     * scalar-only mapper never walks the LAZY {@code disks} collection
     * (no N+1). {@code Pageable} applies the cap+1 {@code LIMIT};
     * {@code order by s.id} makes the truncation set deterministic.
     */
    @Query("""
            select s
            from EndpointDeviceHealthSnapshot s
            where s.tenantId = :tenantId
              and not exists (
                select newer.id
                from EndpointDeviceHealthSnapshot newer
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
    List<EndpointDeviceHealthSnapshot> findLatestPerDeviceForTenant(
            @Param("tenantId") UUID tenantId, Pageable pageable);
}
