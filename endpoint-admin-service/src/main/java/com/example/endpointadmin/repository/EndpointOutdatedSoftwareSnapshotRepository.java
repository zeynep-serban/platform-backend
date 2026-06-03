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
 * composite index ({@code tenant_id, device_id, collected_at, created_at,
 * id}) so PG can satisfy the top-N scan without a sort. The deterministic
 * tiebreaker — {@code collected_at DESC, created_at DESC, id DESC} —
 * matches the index column order.
 *
 * <p>Faz 21.1 PR2b-iv.d-A — per-device reads migrated from derived
 * {@code findByTenantIdAndDeviceId*} to explicit {@code @Query} with the
 * canonical effective-org filter in index-friendly form (Codex 019e8dc7
 * B-D-A sub-slice AGREE, Option A revised — slice c iter-1 lesson
 * applied):
 *
 * <pre>
 *   WHERE s.tenant_id = :orgId
 *     AND (s.org_id = :orgId OR s.org_id IS NULL)
 *     AND s.device_id = :deviceId
 * </pre>
 *
 * <p>V30 {@code CHECK (org_id IS NULL OR org_id = tenant_id)} guarantees
 * semantic equivalence with the canonical effective-org form; the
 * explicit {@code tenant_id = :orgId} predicate keeps the composite index
 * usable. Legacy NULL fallback survives because V20 makes
 * {@code tenant_id NOT NULL}.
 *
 * <p>The first method, {@link #findBySourceCommandResultId(UUID)}, is NOT
 * migrated — it is the idempotency probe for the SUBMIT-result hook and
 * the partial UNIQUE index on {@code source_command_result_id} guarantees
 * per-result uniqueness independent of tenant/org scope.
 *
 * <p>{@code findByTenantDeviceAndPayloadHash} and
 * {@code findLatestPerDeviceForTenant} remain on the original
 * {@code tenantId} signature in this slice; they are migrated in B-D-B
 * (separate PR — Codex 019e8dc7 sub-slice split).
 */
@Repository
public interface EndpointOutdatedSoftwareSnapshotRepository
        extends JpaRepository<EndpointOutdatedSoftwareSnapshot, UUID>,
        EndpointOutdatedSoftwareSnapshotRepositoryCustom {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call. NOT migrated to
     * the effective-org filter — the command-result id is the unique key
     * by itself and the index is already partial.
     */
    Optional<EndpointOutdatedSoftwareSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Canonical PR2b-iv.d-A read — top-N (or full history) per (org,
     * device) sorted by the index tail. Used by:
     *
     * <ul>
     *   <li>{@link #findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(UUID, UUID)}
     *       default wrapper for latest-1 (PageRequest.of(0, 1)) — Codex
     *       019e8dc7 portable LIMIT 1 idiom (no derived
     *       {@code findFirst...Optional} which would emit
     *       {@code NonUniqueResultException} on a ties scenario).</li>
     *   <li>OutdatedSoftwareDiffService.diffLatest +
     *       summarize for the latest-two-history walk.</li>
     * </ul>
     *
     * <p>Index-friendly form (Codex 019e8dbb slice c iter-1 lesson —
     * keep {@code tenant_id = :orgId} explicit so the composite index
     * remains usable; V30 invariant guarantees equivalence with the
     * canonical effective-org form).
     */
    @Query("""
            select s
            from EndpointOutdatedSoftwareSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointOutdatedSoftwareSnapshot>
            findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);

    /**
     * Portable LIMIT 1 default wrapper around
     * {@link #findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(UUID, UUID, Pageable)}
     * — avoids the {@code @Query Optional NonUniqueResultException} trap
     * by going through a 1-element {@code Pageable} cap (Codex 019e8dc7
     * AGREE; same pattern as PR2b-iv.a compliance evaluation slice).
     */
    default Optional<EndpointOutdatedSoftwareSnapshot>
            findFirstVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID orgId, UUID deviceId) {
        List<EndpointOutdatedSoftwareSnapshot> head =
                findVisibleToOrgAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        orgId, deviceId, org.springframework.data.domain.PageRequest.of(0, 1));
        return head.isEmpty() ? Optional.empty() : Optional.of(head.get(0));
    }

    /**
     * Canonical PR2b-iv.d-A read — paged append-only history per (org,
     * device) with the effective-org filter in index-friendly form;
     * ordering is supplied by the caller-built {@link Pageable}
     * {@code Sort} (the service pins
     * {@code collected_at DESC, created_at DESC, id DESC} so it matches
     * the composite index). {@code countQuery} sibling computes total
     * over the same predicate.
     */
    @Query(value = """
            select s
            from EndpointOutdatedSoftwareSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            """,
            countQuery = """
            select count(s)
            from EndpointOutdatedSoftwareSnapshot s
            where s.tenantId = :orgId
              and (s.orgId = :orgId or s.orgId is null)
              and s.deviceId = :deviceId
            """)
    Page<EndpointOutdatedSoftwareSnapshot>
            findVisibleToOrgAndDeviceId(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);

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
     * against the outdated-software snapshots.
     *
     * <p><strong>JPQL, not native</strong> (live-bug fix): a native query
     * with an unqualified {@code FROM endpoint_outdated_software_snapshots}
     * fails on live testai with {@code relation ... does not exist} because
     * the tables live in the non-{@code public} {@code endpoint_admin_service}
     * schema and the connection search_path does not include it. HQL/JPQL
     * is schema-qualified by Hibernate from the entity mapping for every
     * dialect (parity with the working per-device queries).
     *
     * <p>"Latest per device" via greatest-per-group {@code NOT EXISTS} (no
     * strictly-newer snapshot for the same (tenant, device)) with the SAME
     * lexicographic {@code (collected_at, created_at, id)} DESC tiebreaker
     * as the per-device derived query (all three {@code NOT NULL}); the
     * correlated probe rides the
     * {@code idx_endpoint_outdated_software_snapshots_tenant_device_time}
     * index. {@code select s} loads whole entities; the scalar-only mapper
     * never walks the LAZY {@code packages} collection (no N+1).
     * {@code Pageable} applies the cap+1 {@code LIMIT}; {@code order by
     * s.id} makes the truncation set deterministic.
     */
    @Query("""
            select s
            from EndpointOutdatedSoftwareSnapshot s
            where s.tenantId = :tenantId
              and not exists (
                select newer.id
                from EndpointOutdatedSoftwareSnapshot newer
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
    List<EndpointOutdatedSoftwareSnapshot> findLatestPerDeviceForTenant(
            @Param("tenantId") UUID tenantId, Pageable pageable);
}
