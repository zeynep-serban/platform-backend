package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
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
 * BE-022 — read access for hardware inventory snapshots.
 *
 * <p>Latest-per-device query uses the
 * {@code idx_endpoint_hardware_inventory_snapshots_tenant_device_time}
 * composite index for index-only scans. The deterministic
 * tiebreaker — {@code collected_at DESC, created_at DESC, id DESC} —
 * matches the index column order so PG can avoid a sort.
 */
@Repository
public interface EndpointHardwareInventorySnapshotRepository
        extends JpaRepository<EndpointHardwareInventorySnapshot, UUID> {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointHardwareInventorySnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest snapshot per (tenant, device). Composite index lookup +
     * deterministic ordering (Codex 019e7007 iter-3 nice_to_have).
     */
    Optional<EndpointHardwareInventorySnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /** Append-only history per (tenant, device). */
    Page<EndpointHardwareInventorySnapshot> findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * BE-022Q payload-hash deep-equality dedupe probe (Halildeu/platform-backend#327).
     *
     * <p>Secondary idempotency layer behind {@link #findBySourceCommandResultId(UUID)}:
     * when the agent re-collects byte-identical hardware under a
     * <em>different</em> command-result (so the source_command_result_id
     * probe misses), this returns the most-recent snapshot whose
     * {@code payload_hash_sha256} equals the incoming payload hash, so the
     * service can no-op instead of appending a duplicate row.
     *
     * <h4>Why this query avoids the {@code lower(bytea)} grammar bug</h4>
     *
     * The original BE-022Q deep-equality query (surfaced in PR #326 Codex
     * peer review) compared the hash case-insensitively with
     * {@code lower(s.payloadHashSha256) = lower(:payloadHash)} — the same
     * shape that produced the live {@code function lower(bytea) does not
     * exist} {@code SQLGrammarException} on the BE-020I software-inventory
     * path (commit {@code 9a2831d5}). With a nullable {@code String}
     * parameter, Hibernate 6 + the PostgreSQL JDBC driver bind the param
     * as {@code bytea} when the value is {@code null}, and PG's overload
     * resolver then tries the non-existent {@code lower(bytea)}.
     *
     * <p>Two-part fix, type-safe by construction:
     * <ol>
     *   <li><strong>No {@code lower()} at all.</strong> The hash is always
     *       exactly 64 lowercase hex chars — {@code sha256Hex(...)} emits
     *       {@code HexFormat.of().formatHex(...)} (lowercase) and the DB
     *       CHECK {@code payload_hash_sha256 ~ '^[a-f0-9]{64}$'} (V13)
     *       enforces lowercase storage. A direct {@code =} on the
     *       {@code VARCHAR(64)} column (V14) is therefore both correct and
     *       case-exact; case folding is dead weight that only reintroduces
     *       the {@code lower()} overload-resolution risk.</li>
     *   <li><strong>Explicit {@code cast(:payloadHash as string)}.</strong>
     *       Forces Hibernate to emit {@code CAST(? AS varchar)} so the
     *       comparison resolves against {@code varchar} unambiguously and
     *       the driver cannot infer {@code bytea} for the bound parameter
     *       regardless of the surrounding binding context (same guard the
     *       BE-020I fix applies at every nullable-String reference).</li>
     * </ol>
     *
     * <p>The {@code order by collected_at desc, created_at desc, id desc}
     * shape reuses the
     * {@code idx_endpoint_hardware_inventory_snapshots_tenant_device_time}
     * composite index. Returns a {@code List} (capped to one row by the
     * caller via {@code PageRequest.of(0, 1)}) rather than {@code Optional}
     * because more than one snapshot can legitimately carry the same hash
     * (append-only history of byte-identical re-collections) and an
     * {@code Optional} return would raise {@code NonUniqueResultException};
     * the caller takes the head as the canonical "latest identical".
     */
    @Query("""
            select s
            from EndpointHardwareInventorySnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointHardwareInventorySnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);
}
