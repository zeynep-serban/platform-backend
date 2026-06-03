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
        // BE-024c v2-c-pre-2-C-A (Codex 019e89a3 iter-3 AGREE) — full
        // source-pair ordering tuple guard via direct EXCLUDED.source_* >=
        // c.source_* comparison. Three guards combined:
        //   1. Source-pair tuple guard (newer-or-same source pair only):
        //      blocks stale-overwrites under overlapping listener tx.
        //   2. From-downgrade guard: NOT (c.from non-null AND EXCLUDED.from
        //      null) blocks OK -> INSUFFICIENT walking back to a less
        //      informative row when source ids match.
        //   3. Any-column-differs predicate: identical-payload re-ingests
        //      become a true SQL no-op (zero WAL, zero row-version churn).
        // Insert path bypasses the guard; initial-row source integrity is
        // owned by the listener summarize() against latest committed state
        // (Codex iter-4 guardrail #5).
        // Faz 21.1 PR2c (Codex 019e8e29 iter-1 REVISE #2 absorb) —
        // canonical org_id write at the INSERT path: include
        // org_id = tenant_id in the INSERT column list so genuinely-new
        // rows land canonical immediately.
        // ON CONFLICT DO UPDATE SET also includes org_id = EXCLUDED.org_id
        // BUT the SET only applies when the WHERE clause below (source-
        // pair / from-downgrade / any-column-differs guards) returns true.
        // No-op conflicts (identical payload) do NOT refresh org_id from
        // this code path — the V33 trigger handles legacy NULL re-fill
        // independently on every INSERT/UPDATE. Combined with the
        // migration backfill, the test cluster reaches mismatch=0 even
        // without an explicit drift heal here; the cleanup PR will
        // sequence the schema migration (conflict target + UNIQUE +
        // FK + repository + grid join) before tenant_id drop.
        String sql = """
                INSERT INTO %s AS c (
                    id, tenant_id, org_id, device_id,
                    from_history_id, to_history_id,
                    status,
                    added_count, removed_count, version_changed_count,
                    source_captured_at, source_created_at, source_row_id,
                    computed_at
                ) VALUES (
                    :id, :tenantId, :tenantId, :deviceId,
                    :fromHistoryId, :toHistoryId,
                    :status,
                    :added, :removed, :versionChanged,
                    :sourceCapturedAt, :sourceCreatedAt, :sourceRowId,
                    :computedAt
                )
                ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                    org_id = EXCLUDED.org_id,
                    from_history_id = EXCLUDED.from_history_id,
                    to_history_id = EXCLUDED.to_history_id,
                    status = EXCLUDED.status,
                    added_count = EXCLUDED.added_count,
                    removed_count = EXCLUDED.removed_count,
                    version_changed_count = EXCLUDED.version_changed_count,
                    source_captured_at = EXCLUDED.source_captured_at,
                    source_created_at = EXCLUDED.source_created_at,
                    source_row_id = EXCLUDED.source_row_id,
                    computed_at = EXCLUDED.computed_at
                WHERE
                    (   -- source-pair ordering tuple guard (Codex iter-3 #1)
                        EXCLUDED.source_captured_at > c.source_captured_at
                        OR (EXCLUDED.source_captured_at = c.source_captured_at
                            AND EXCLUDED.source_created_at > c.source_created_at)
                        OR (EXCLUDED.source_captured_at = c.source_captured_at
                            AND EXCLUDED.source_created_at = c.source_created_at
                            AND EXCLUDED.source_row_id >= c.source_row_id)
                    )
                AND
                    (   -- from-downgrade guard (Codex iter-4 #4)
                        NOT (c.from_history_id IS NOT NULL
                             AND EXCLUDED.from_history_id IS NULL)
                    )
                AND
                    (   -- any-column-differs (identical-payload no-op).
                        -- Codex 019e89a3 iter-4 absorb: source_* tuple
                        -- columns are part of the diff so a row stamped
                        -- with the wrong tuple (e.g. by a legacy epoch-
                        -- factory call site) self-heals on the next
                        -- production refresh.
                        c.status IS DISTINCT FROM EXCLUDED.status
                        OR c.from_history_id IS DISTINCT FROM EXCLUDED.from_history_id
                        OR c.to_history_id IS DISTINCT FROM EXCLUDED.to_history_id
                        OR c.added_count <> EXCLUDED.added_count
                        OR c.removed_count <> EXCLUDED.removed_count
                        OR c.version_changed_count <> EXCLUDED.version_changed_count
                        OR c.source_captured_at IS DISTINCT FROM EXCLUDED.source_captured_at
                        OR c.source_created_at  IS DISTINCT FROM EXCLUDED.source_created_at
                        OR c.source_row_id      IS DISTINCT FROM EXCLUDED.source_row_id
                    )
                """.formatted(table);

        Query q = em.createNativeQuery(sql);
        q.setParameter("id", UUID.randomUUID());
        q.setParameter("tenantId", tenantId);
        q.setParameter("deviceId", deviceId);
        q.setParameter("fromHistoryId", summary.fromHistoryId());
        q.setParameter("toHistoryId", summary.toHistoryId());
        q.setParameter("status", summary.status().name());
        q.setParameter("added", summary.addedCount());
        q.setParameter("sourceCapturedAt", summary.sourceCapturedAt());
        q.setParameter("sourceCreatedAt", summary.sourceCreatedAt());
        q.setParameter("sourceRowId", summary.sourceRowId());
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
        // BE-024c v2-c-pre-2-C-A (Codex 019e89a3 iter-3 AGREE) — outdated
        // mirror of the software source-pair ordering tuple guard. See
        // software UPSERT for full rationale.
        // Faz 21.1 PR2c (Codex 019e8e29 iter-1 REVISE #2 absorb) —
        // outdated mirror of the canonical org_id write (see software
        // UPSERT comment above for full rationale on no-op conflict
        // semantics + cleanup PR sequence).
        String sql = """
                INSERT INTO %s AS c (
                    id, tenant_id, org_id, device_id,
                    from_snapshot_id, to_snapshot_id,
                    status,
                    added_count, removed_count, version_changed_count,
                    available_version_bumped_count,
                    source_captured_at, source_created_at, source_row_id,
                    computed_at
                ) VALUES (
                    :id, :tenantId, :tenantId, :deviceId,
                    :fromSnapshotId, :toSnapshotId,
                    :status,
                    :added, :removed, :versionChanged,
                    :availableVersionBumped,
                    :sourceCapturedAt, :sourceCreatedAt, :sourceRowId,
                    :computedAt
                )
                ON CONFLICT (tenant_id, device_id) DO UPDATE SET
                    org_id = EXCLUDED.org_id,
                    from_snapshot_id = EXCLUDED.from_snapshot_id,
                    to_snapshot_id = EXCLUDED.to_snapshot_id,
                    status = EXCLUDED.status,
                    added_count = EXCLUDED.added_count,
                    removed_count = EXCLUDED.removed_count,
                    version_changed_count = EXCLUDED.version_changed_count,
                    available_version_bumped_count = EXCLUDED.available_version_bumped_count,
                    source_captured_at = EXCLUDED.source_captured_at,
                    source_created_at = EXCLUDED.source_created_at,
                    source_row_id = EXCLUDED.source_row_id,
                    computed_at = EXCLUDED.computed_at
                WHERE
                    (   -- source-pair ordering tuple guard
                        EXCLUDED.source_captured_at > c.source_captured_at
                        OR (EXCLUDED.source_captured_at = c.source_captured_at
                            AND EXCLUDED.source_created_at > c.source_created_at)
                        OR (EXCLUDED.source_captured_at = c.source_captured_at
                            AND EXCLUDED.source_created_at = c.source_created_at
                            AND EXCLUDED.source_row_id >= c.source_row_id)
                    )
                AND
                    (   -- from-downgrade guard
                        NOT (c.from_snapshot_id IS NOT NULL
                             AND EXCLUDED.from_snapshot_id IS NULL)
                    )
                AND
                    (   -- any-column-differs (identical-payload no-op).
                        -- Codex 019e89a3 iter-4 absorb: source_* tuple
                        -- columns are part of the diff (mirror of software).
                        c.status IS DISTINCT FROM EXCLUDED.status
                        OR c.from_snapshot_id IS DISTINCT FROM EXCLUDED.from_snapshot_id
                        OR c.to_snapshot_id IS DISTINCT FROM EXCLUDED.to_snapshot_id
                        OR c.added_count <> EXCLUDED.added_count
                        OR c.removed_count <> EXCLUDED.removed_count
                        OR c.version_changed_count <> EXCLUDED.version_changed_count
                        OR c.available_version_bumped_count <> EXCLUDED.available_version_bumped_count
                        OR c.source_captured_at IS DISTINCT FROM EXCLUDED.source_captured_at
                        OR c.source_created_at  IS DISTINCT FROM EXCLUDED.source_created_at
                        OR c.source_row_id      IS DISTINCT FROM EXCLUDED.source_row_id
                    )
                """.formatted(table);

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
        q.setParameter("sourceCapturedAt", summary.sourceCapturedAt());
        q.setParameter("sourceCreatedAt", summary.sourceCreatedAt());
        q.setParameter("sourceRowId", summary.sourceRowId());
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
