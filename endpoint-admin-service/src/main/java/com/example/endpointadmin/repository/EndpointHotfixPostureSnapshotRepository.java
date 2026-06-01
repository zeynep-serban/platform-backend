package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
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
 * BE — read/append access for hotfix posture snapshots (Faz 22.5,
 * AG-037 ingest). Mirrors the BE-024/AG-036
 * {@code EndpointOutdatedSoftwareSnapshotRepository} precedent EXACTLY,
 * plus the custom native-insert write path
 * ({@link EndpointHotfixPostureSnapshotRepositoryCustom}).
 *
 * <p>Latest-per-device query uses the
 * {@code idx_endpoint_hotfix_post_snap_tnt_dev_time} composite index for
 * index-only scans. The deterministic tiebreaker —
 * {@code collected_at DESC, created_at DESC, id DESC} — matches the
 * index column order so PG can avoid a sort.
 *
 * <h3>All reads JPQL — NEVER native</h3>
 *
 * <p>The BE-022Q {@code lower(bytea)} bug (Hibernate 6 + PG JDBC bind null
 * String as bytea, PG resolver tries non-existent {@code lower(bytea)} →
 * SQLGrammarException) AND the live #342 bug (native unqualified
 * {@code FROM endpoint_hotfix_posture_snapshots} fails on the non-public
 * {@code endpoint_admin_service} schema because connection
 * {@code search_path} excludes it) BOTH force JPQL only on the read side.
 * HQL/JPQL is schema-qualified by Hibernate from the entity mapping for
 * every dialect (parity with the AG-036 V20 derived/JPQL queries).
 *
 * <p>The custom native insert path
 * ({@link EndpointHotfixPostureSnapshotRepositoryCustom}) IS schema-
 * qualified explicitly via the {@code endpoint-admin.service} resolved
 * default schema (mirror V20 impl).
 */
@Repository
public interface EndpointHotfixPostureSnapshotRepository
        extends JpaRepository<EndpointHotfixPostureSnapshot, UUID>,
        EndpointHotfixPostureSnapshotRepositoryCustom {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointHotfixPostureSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest snapshot per (tenant, device). Composite index lookup +
     * deterministic ordering.
     */
    Optional<EndpointHotfixPostureSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /**
     * Hash-conflict winner re-lookup for the dual-idempotency same-hash
     * race path (Codex 019e81fe iter-3 P1.1 + iter-4): when the targetless
     * {@code ON CONFLICT DO NOTHING} write hits the
     * {@code (tenant, device, payload_hash)} UNIQUE, the service first
     * tries the source_command_result_id probe (when non-null) and falls
     * through to this method. The HARD UNIQUE makes a multi-row result
     * impossible going forward, but deterministic ordering preserves
     * correctness against bad historical seeds or a future migration that
     * relaxed the UNIQUE.
     */
    Optional<EndpointHotfixPostureSnapshot>
            findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, String payloadHashSha256);

    /** Append-only history per (tenant, device). */
    Page<EndpointHotfixPostureSnapshot>
            findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Page over all snapshots for a (tenant, device) without an inline
     * {@code OrderBy} — ordering is supplied by the caller-built
     * {@link Pageable} {@code Sort} (the test atomicity helper uses this
     * to count rows by device under a shared container). Composite-index
     * friendly when the caller pins {@code collected_at DESC,
     * created_at DESC, id DESC}.
     */
    Page<EndpointHotfixPostureSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Payload-hash deep-equality dedupe probe — mirrors the BE-022Q lesson
     * + the AG-036 outdated-software precedent.
     *
     * <p>Secondary idempotency layer behind {@link #findBySourceCommandResultId(UUID)}:
     * when the agent re-collects byte-identical posture under a
     * <em>different</em> command-result (so the source_command_result_id
     * probe misses), this returns the most-recent snapshot whose
     * {@code payload_hash_sha256} equals the incoming payload hash, so
     * the service can no-op instead of appending a duplicate row. After
     * Codex 019e81fe iter-3 the HARD UNIQUE
     * {@code (tenant_id, device_id, payload_hash_sha256)} also enforces
     * this at the DB layer; this probe stays as the pre-insert fast path
     * so a same-hash collision short-circuits before reaching the DB.
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
     *       CHECK {@code payload_hash_sha256 ~ '^[a-f0-9]{64}$'} (V22)
     *       enforces lowercase storage. A direct {@code =} on the
     *       {@code VARCHAR(64)} column is therefore both correct and
     *       case-exact.</li>
     *   <li><strong>Explicit {@code cast(:payloadHash as string)}.</strong>
     *       Forces Hibernate to emit {@code CAST(? AS varchar)} so the
     *       comparison resolves against {@code varchar} unambiguously and
     *       the driver cannot infer {@code bytea} for the bound
     *       parameter.</li>
     * </ol>
     */
    @Query("""
            select s
            from EndpointHotfixPostureSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointHotfixPostureSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);

    /**
     * Fleet-wide LATEST snapshot per device for a tenant (Faz 22.5,
     * future fleet bulk endpoint). Mirrors the AG-033/AG-036
     * {@code findLatestPerDeviceForTenant} against hotfix posture
     * snapshots.
     *
     * <p><strong>JPQL, not native</strong> (live-bug fix): a native query
     * with an unqualified {@code FROM endpoint_hotfix_posture_snapshots}
     * fails on live testai with {@code relation ... does not exist}
     * because the tables live in the non-{@code public}
     * {@code endpoint_admin_service} schema and the connection
     * {@code search_path} does not include it. HQL/JPQL is
     * schema-qualified by Hibernate from the entity mapping for every
     * dialect (parity with the working per-device queries).
     *
     * <p>"Latest per device" via greatest-per-group {@code NOT EXISTS}
     * (no strictly-newer snapshot for the same (tenant, device)) with
     * the SAME lexicographic {@code (collected_at, created_at, id)} DESC
     * tiebreaker as the per-device derived query (all three
     * {@code NOT NULL}); the correlated probe rides the
     * {@code idx_endpoint_hotfix_post_snap_tnt_dev_time} index.
     * {@code select s} loads whole entities; the scalar-only mapper
     * never walks the LAZY child collections (no N+1).
     * {@code Pageable} applies the cap+1 {@code LIMIT};
     * {@code order by s.id} makes the truncation set deterministic.
     */
    @Query("""
            select s
            from EndpointHotfixPostureSnapshot s
            where s.tenantId = :tenantId
              and not exists (
                select newer.id
                from EndpointHotfixPostureSnapshot newer
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
    List<EndpointHotfixPostureSnapshot> findLatestPerDeviceForTenant(
            @Param("tenantId") UUID tenantId, Pageable pageable);
}
