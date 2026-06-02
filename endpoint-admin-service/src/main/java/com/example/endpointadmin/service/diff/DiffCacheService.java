package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-024c diff summary cache writer (Faz 22.5 P2-A v2-c-pre-2-A, Codex
 * 019e88b5 iter-5 AGREE — Step 1 of the 7-step execution order).
 *
 * <p>Owns the race-safe write path into {@code endpoint_software_diff_cache}
 * and {@code endpoint_outdated_software_diff_cache}. Reads continue through
 * the JPA repositories (already shipped in v2-c-pre PR #381). v2-d grid
 * SCHEMA v5 (a separate later PR) joins these cache tables instead of
 * recomputing the diff per grid row.
 *
 * <h2>Scope of THIS PR (v2-c-pre-2-A)</h2>
 *
 * <p>Dormant write primitive + PG IT idempotency only — no production
 * caller fires this writer yet. Ingest hooks
 * ({@link com.example.endpointadmin.service.EndpointSoftwareInventoryService#ingest}
 * and {@link com.example.endpointadmin.service.EndpointOutdatedSoftwareService#ingest})
 * + backfill worker + admin endpoint deliberately deferred to
 * v2-c-pre-2-B / -C so each PR stays inside one Codex review window and
 * one branch lifetime (parallel-session collision guard, R-CONTRACT-2).
 * Source-freshness ordering ships with the ingest hook PR.
 *
 * <h2>Why native ON CONFLICT UPSERT, not JPA save()</h2>
 *
 * <p>v2-c-pre-2 ingest hooks fire from {@code @Transactional} ingest paths
 * that may run concurrently for the same {@code (tenantId, deviceId)} when
 * two captures arrive back-to-back. JPA {@code save()} would either
 * (a) INSERT and fail on the {@code swdc_tenant_device_uq} UNIQUE collision
 * or (b) SELECT + UPDATE and race against a parallel INSERT. PostgreSQL's
 * {@code INSERT ... ON CONFLICT DO UPDATE} closes the race in one round-trip
 * atomic at the storage layer — Codex 019e88b5 iter-2 must_fix #2.
 *
 * <h2>Identical-payload no-op semantics</h2>
 *
 * <p>Codex 019e88b5 iter-2 must_fix #4: the ingest hook fires on every
 * inventory/outdated ingest, but most of those produce the same diff
 * summary the cache already holds (drawer/cache parity check passed in
 * v2-c-pre). Re-writing the same row would burn a WAL entry + an extra
 * row-version + bloat the autovacuum queue for no semantic delta.
 *
 * <p>The native UPSERT adds a SQL-level "is anything different" predicate
 * to the {@code ON CONFLICT DO UPDATE} clause; if the incoming source-id
 * pair, status, and counts all match the stored row, the conflict path
 * becomes a no-op (PG's {@code DO UPDATE WHERE} is the canonical pattern).
 * {@code computed_at} is also updated only when something else changes —
 * otherwise a cold row would drift its timestamp on every ingest, defeating
 * staleness signals.
 *
 * <h2>Caller contract (source-freshness ordering)</h2>
 *
 * <p>Codex 019e8964 iter-2 absorb: this writer is row-level race-safe but
 * NOT cache-correctness safe on its own. A {@code computed_at} wall-clock
 * guard was tried in iter-1 but Codex correctly pointed out that it only
 * orders write-time, not source-pair freshness — a stale tx that read an
 * older history pair earlier and called upsert later (with a fresher
 * {@code Instant.now()}) would still regress the cache to an older state.
 * The correct cache-correctness guard is source-pair ordering on the FK
 * side (compare {@code captured_at, created_at, id} tuple of incoming
 * {@code to_history_id} against the stored row's), which is a non-trivial
 * subquery best wired up in the ingest hook PR (v2-c-pre-2-B) where the
 * caller-side contract is end-to-end with PG IT proof.
 *
 * <p>Until then, the caller (ingest hook / backfill worker) MUST call
 * {@code summarize()} against the latest captured source pair INSIDE the
 * same transaction that just persisted it, so an older summary cannot
 * reach this writer ahead of a newer one. Callers that do not respect
 * this contract MAY regress the cache row to a stale state under
 * concurrent ingest.
 *
 * <h2>Schema-qualified SQL</h2>
 *
 * <p>The native query is schema-qualified because
 * {@code hibernate.default_schema} only governs JPA / Hibernate-generated
 * queries; native queries hit the PG connection {@code search_path}, which
 * Spring Boot's default-schema property does not set. Reading the schema
 * from the same property keeps the writer aligned with the JPA layer
 * ({@code @Table(name=...)} resolved under {@code default_schema}).
 */
@Service
public class DiffCacheService {

    private static final Logger log = LoggerFactory.getLogger(DiffCacheService.class);

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    @PersistenceContext
    private EntityManager em;

    /**
     * Schema-qualifies a table name with fail-closed identifier validation
     * (Codex 019e8964 iter-1 Low #2 absorb — single-source qualifier helper
     * mirroring {@code ComplianceGapRepository.qualified()} and
     * {@code DeviceGridQueryBuilder.qualified()}).
     */
    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) return tableName;
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }

    /**
     * Upserts the BE-024 software diff cache row for {@code (tenantId,
     * deviceId)} using the supplied summary. The conflict branch only writes
     * when at least one of {status, fromHistoryId, toHistoryId, addedCount,
     * removedCount, versionChangedCount} differs from the stored row —
     * identical-payload re-ingests become a true no-op (zero WAL, zero
     * row-version churn).
     *
     * <p>Returns {@code true} when the row was inserted or updated;
     * {@code false} when the {@code ON CONFLICT DO UPDATE WHERE} filter
     * suppressed the update (identical payload).
     *
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if the summary violates the status
     *         shape / non-OK counts-zero invariant client-side (saves a
     *         round-trip; the same invariant is V27-enforced as a CHECK,
     *         but a generic "violates constraint" error is less precise)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean upsertSoftwareDiffCache(UUID tenantId, UUID deviceId,
                                            SoftwareDiffSummary summary) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(summary, "summary");

        validateSoftwareShape(summary);

        String table = qualified("endpoint_software_diff_cache");
        String historyTable = qualified("endpoint_software_inventory_state_history");
        // Codex 019e8964 iter-4 AGREE: source-pair ordering guard wired
        // into the UPSERT WHERE so stale refresh listeners cannot regress
        // the cache row to an older source pair. Existing
        // {@code to_history_id IS NULL} (NO_HISTORY) treats any incoming
        // source-pair as progression; existing source-pair vs incoming
        // {@code NO_HISTORY/INSUFFICIENT_HISTORY} (incoming
        // {@code to_history_id IS NULL}) is rejected as downgrade.
        // When both source-pair rows exist, the incoming
        // {@code to_history_id}'s {@code (captured_at, created_at, id)}
        // tuple is compared to the stored row's via subquery on the
        // history table — {@code device_id} equality is asserted explicitly
        // (Codex iter-4 guardrail #3) so a cross-device row in the
        // history table can never accidentally satisfy the guard.
        // {@code from_history_id} downgrade
        // ({@code OK/NO_CHANGE} -> {@code INSUFFICIENT_HISTORY}) is also
        // blocked (Codex iter-4 guardrail #4): same {@code to_history_id}
        // with non-null -> null {@code from_history_id} would otherwise
        // walk a more informative row back to a less informative one.
        // Insert path (no conflict) bypasses the guard; initial row
        // source integrity is owned by the caller (listener summarize
        // against latest committed state) and DB FK tenant integrity
        // (Codex iter-4 guardrail #5).
        String sql = """
                INSERT INTO %s AS c (
                    id, tenant_id, device_id,
                    from_history_id, to_history_id,
                    status,
                    added_count, removed_count, version_changed_count,
                    computed_at
                ) VALUES (
                    :id, :tenantId, :deviceId,
                    :fromHistoryId, :toHistoryId,
                    :status,
                    :added, :removed, :versionChanged,
                    :computedAt
                )
                ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                    from_history_id = EXCLUDED.from_history_id,
                    to_history_id = EXCLUDED.to_history_id,
                    status = EXCLUDED.status,
                    added_count = EXCLUDED.added_count,
                    removed_count = EXCLUDED.removed_count,
                    version_changed_count = EXCLUDED.version_changed_count,
                    computed_at = EXCLUDED.computed_at
                WHERE
                    (  -- source-pair ordering guard (Codex iter-4 #3 + #4)
                       c.to_history_id IS NULL
                       OR (
                           EXCLUDED.to_history_id IS NOT NULL
                           AND NOT (c.from_history_id IS NOT NULL
                                    AND EXCLUDED.from_history_id IS NULL)
                           AND EXISTS (
                               SELECT 1
                               FROM %s existing_h
                               JOIN %s incoming_h
                                 ON incoming_h.tenant_id = existing_h.tenant_id
                                AND incoming_h.device_id = existing_h.device_id
                               WHERE existing_h.id = c.to_history_id
                                 AND existing_h.tenant_id = c.tenant_id
                                 AND existing_h.device_id = c.device_id
                                 AND incoming_h.id = EXCLUDED.to_history_id
                                 AND (
                                     incoming_h.captured_at > existing_h.captured_at
                                     OR (incoming_h.captured_at = existing_h.captured_at
                                         AND incoming_h.created_at > existing_h.created_at)
                                     OR (incoming_h.captured_at = existing_h.captured_at
                                         AND incoming_h.created_at = existing_h.created_at
                                         AND incoming_h.id >= existing_h.id)
                                 )
                           )
                       )
                    )
                AND
                    (  -- any column differs (identical-payload no-op)
                       c.status IS DISTINCT FROM EXCLUDED.status
                       OR c.from_history_id IS DISTINCT FROM EXCLUDED.from_history_id
                       OR c.to_history_id IS DISTINCT FROM EXCLUDED.to_history_id
                       OR c.added_count <> EXCLUDED.added_count
                       OR c.removed_count <> EXCLUDED.removed_count
                       OR c.version_changed_count <> EXCLUDED.version_changed_count
                    )
                """.formatted(table, historyTable, historyTable);

        Query q = em.createNativeQuery(sql);
        q.setParameter("id", UUID.randomUUID());
        q.setParameter("tenantId", tenantId);
        q.setParameter("deviceId", deviceId);
        q.setParameter("fromHistoryId", summary.fromHistoryId());
        q.setParameter("toHistoryId", summary.toHistoryId());
        q.setParameter("status", summary.status().name());
        q.setParameter("added", summary.addedCount());
        q.setParameter("removed", summary.removedCount());
        q.setParameter("versionChanged", summary.versionChangedCount());
        q.setParameter("computedAt", Instant.now());

        int affected = q.executeUpdate();
        if (affected == 0) {
            log.trace("DiffCache software no-op tenant={} device={}", tenantId, deviceId);
            return false;
        }
        return true;
    }

    /**
     * Upserts the BE-024b outdated-software diff cache row. Same race-safe
     * native UPSERT pattern as {@link #upsertSoftwareDiffCache}; carries a
     * 4th count for {@code availableVersionBumpedCount}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean upsertOutdatedDiffCache(UUID tenantId, UUID deviceId,
                                            OutdatedDiffSummary summary) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(summary, "summary");

        validateOutdatedShape(summary);

        String table = qualified("endpoint_outdated_software_diff_cache");
        String snapshotTable = qualified("endpoint_outdated_software_snapshots");
        // Codex 019e8964 iter-4 AGREE: source-pair ordering guard mirror
        // for outdated cache. See software case for rationale; tuple is
        // (collected_at, created_at, id) on the outdated snapshot table.
        String sql = """
                INSERT INTO %s AS c (
                    id, tenant_id, device_id,
                    from_snapshot_id, to_snapshot_id,
                    status,
                    added_count, removed_count, version_changed_count,
                    available_version_bumped_count,
                    computed_at
                ) VALUES (
                    :id, :tenantId, :deviceId,
                    :fromSnapshotId, :toSnapshotId,
                    :status,
                    :added, :removed, :versionChanged,
                    :availableVersionBumped,
                    :computedAt
                )
                ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                    from_snapshot_id = EXCLUDED.from_snapshot_id,
                    to_snapshot_id = EXCLUDED.to_snapshot_id,
                    status = EXCLUDED.status,
                    added_count = EXCLUDED.added_count,
                    removed_count = EXCLUDED.removed_count,
                    version_changed_count = EXCLUDED.version_changed_count,
                    available_version_bumped_count = EXCLUDED.available_version_bumped_count,
                    computed_at = EXCLUDED.computed_at
                WHERE
                    (
                       c.to_snapshot_id IS NULL
                       OR (
                           EXCLUDED.to_snapshot_id IS NOT NULL
                           AND NOT (c.from_snapshot_id IS NOT NULL
                                    AND EXCLUDED.from_snapshot_id IS NULL)
                           AND EXISTS (
                               SELECT 1
                               FROM %s existing_s
                               JOIN %s incoming_s
                                 ON incoming_s.tenant_id = existing_s.tenant_id
                                AND incoming_s.device_id = existing_s.device_id
                               WHERE existing_s.id = c.to_snapshot_id
                                 AND existing_s.tenant_id = c.tenant_id
                                 AND existing_s.device_id = c.device_id
                                 AND incoming_s.id = EXCLUDED.to_snapshot_id
                                 AND (
                                     incoming_s.collected_at > existing_s.collected_at
                                     OR (incoming_s.collected_at = existing_s.collected_at
                                         AND incoming_s.created_at > existing_s.created_at)
                                     OR (incoming_s.collected_at = existing_s.collected_at
                                         AND incoming_s.created_at = existing_s.created_at
                                         AND incoming_s.id >= existing_s.id)
                                 )
                           )
                       )
                    )
                AND
                    (
                       c.status IS DISTINCT FROM EXCLUDED.status
                       OR c.from_snapshot_id IS DISTINCT FROM EXCLUDED.from_snapshot_id
                       OR c.to_snapshot_id IS DISTINCT FROM EXCLUDED.to_snapshot_id
                       OR c.added_count <> EXCLUDED.added_count
                       OR c.removed_count <> EXCLUDED.removed_count
                       OR c.version_changed_count <> EXCLUDED.version_changed_count
                       OR c.available_version_bumped_count <> EXCLUDED.available_version_bumped_count
                    )
                """.formatted(table, snapshotTable, snapshotTable);

        Query q = em.createNativeQuery(sql);
        q.setParameter("id", UUID.randomUUID());
        q.setParameter("tenantId", tenantId);
        q.setParameter("deviceId", deviceId);
        q.setParameter("fromSnapshotId", summary.fromSnapshotId());
        q.setParameter("toSnapshotId", summary.toSnapshotId());
        q.setParameter("status", summary.status().name());
        q.setParameter("added", summary.addedCount());
        q.setParameter("removed", summary.removedCount());
        q.setParameter("versionChanged", summary.versionChangedCount());
        q.setParameter("availableVersionBumped", summary.availableVersionBumpedCount());
        q.setParameter("computedAt", Instant.now());

        int affected = q.executeUpdate();
        if (affected == 0) {
            log.trace("DiffCache outdated no-op tenant={} device={}", tenantId, deviceId);
            return false;
        }
        return true;
    }

    /**
     * Defensive client-side check mirroring V27 {@code swdc_status_shape_ck}
     * and {@code swdc_non_ok_counts_zero_ck}.
     */
    private static void validateSoftwareShape(SoftwareDiffSummary s) {
        AdminSoftwareInventoryDiffResponse.DiffStatus status = s.status();
        switch (status) {
            case NO_HISTORY -> {
                if (s.fromHistoryId() != null || s.toHistoryId() != null) {
                    throw new IllegalArgumentException(
                            "NO_HISTORY requires both ids null");
                }
                requireZeroCountsSoftware(s, status);
            }
            case INSUFFICIENT_HISTORY -> {
                if (s.fromHistoryId() != null || s.toHistoryId() == null) {
                    throw new IllegalArgumentException(
                            "INSUFFICIENT_HISTORY requires fromHistoryId null and toHistoryId set");
                }
                requireZeroCountsSoftware(s, status);
            }
            case NO_CHANGE -> {
                if (s.fromHistoryId() == null || s.toHistoryId() == null) {
                    throw new IllegalArgumentException(
                            "NO_CHANGE requires both ids set");
                }
                requireZeroCountsSoftware(s, status);
            }
            case OK -> {
                if (s.fromHistoryId() == null || s.toHistoryId() == null) {
                    throw new IllegalArgumentException(
                            "OK requires both ids set");
                }
                if (s.addedCount() < 0 || s.removedCount() < 0 || s.versionChangedCount() < 0) {
                    throw new IllegalArgumentException(
                            "OK counts must be non-negative");
                }
            }
        }
    }

    private static void requireZeroCountsSoftware(
            SoftwareDiffSummary s,
            AdminSoftwareInventoryDiffResponse.DiffStatus status) {
        if (s.addedCount() != 0 || s.removedCount() != 0 || s.versionChangedCount() != 0) {
            throw new IllegalArgumentException(
                    status + " requires all counts == 0");
        }
    }

    private static void validateOutdatedShape(OutdatedDiffSummary s) {
        AdminOutdatedSoftwareDiffResponse.DiffStatus status = s.status();
        switch (status) {
            case NO_HISTORY -> {
                if (s.fromSnapshotId() != null || s.toSnapshotId() != null) {
                    throw new IllegalArgumentException(
                            "NO_HISTORY requires both ids null");
                }
                requireZeroCountsOutdated(s, status);
            }
            case INSUFFICIENT_HISTORY -> {
                if (s.fromSnapshotId() != null || s.toSnapshotId() == null) {
                    throw new IllegalArgumentException(
                            "INSUFFICIENT_HISTORY requires fromSnapshotId null and toSnapshotId set");
                }
                requireZeroCountsOutdated(s, status);
            }
            case NO_CHANGE -> {
                if (s.fromSnapshotId() == null || s.toSnapshotId() == null) {
                    throw new IllegalArgumentException(
                            "NO_CHANGE requires both ids set");
                }
                requireZeroCountsOutdated(s, status);
            }
            case OK -> {
                if (s.fromSnapshotId() == null || s.toSnapshotId() == null) {
                    throw new IllegalArgumentException(
                            "OK requires both ids set");
                }
                if (s.addedCount() < 0 || s.removedCount() < 0
                        || s.versionChangedCount() < 0
                        || s.availableVersionBumpedCount() < 0) {
                    throw new IllegalArgumentException(
                            "OK counts must be non-negative");
                }
            }
        }
    }

    private static void requireZeroCountsOutdated(
            OutdatedDiffSummary s,
            AdminOutdatedSoftwareDiffResponse.DiffStatus status) {
        if (s.addedCount() != 0 || s.removedCount() != 0
                || s.versionChangedCount() != 0
                || s.availableVersionBumpedCount() != 0) {
            throw new IllegalArgumentException(
                    status + " requires all counts == 0");
        }
    }
}
