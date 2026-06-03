package com.example.endpointadmin.service.diff;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillService} — explicit
 * tenant-scoped sweep that catches up cache rows missed by the AFTER_COMMIT
 * listener path (e.g. devices ingested before v2-c-pre-2-C-A landed, or
 * cache rows lagged by a listener failure that the listener's catch-log
 * swallowed).
 *
 * <p>Each per-device refresh runs in {@link
 * org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} via
 * the injected {@link DiffCacheBackfillDeviceRefresher} (Codex 019e8a09
 * iter-1 must-fix #1: extracting the @Transactional method into a separate
 * bean is required because Spring's proxy-based AOP bypasses
 * @Transactional on self-invocation; calling refreshOneDevice via this
 * separate bean ensures the transactional proxy actually fires).
 *
 * <p>The writer source-pair guard from v2-c-pre-2-C-A keeps the sweep
 * idempotent — a cache row already at the latest source tuple stays
 * untouched and is counted as {@code unchanged}.
 *
 * <p>Codex 019e89e8 iter-5 AGREE on split: this is the v2-c-pre-2-C-B
 * scope; the AFTER_COMMIT listener path from v2-c-pre-2-C-A handles the
 * real-time refresh, the backfill handles the catch-up.
 */
@Service
public class DiffCacheBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DiffCacheBackfillService.class);

    private final DiffCacheBackfillDeviceRefresher deviceRefresher;
    private final JdbcTemplate jdbc;
    private final String schema;

    public DiffCacheBackfillService(
            DiffCacheBackfillDeviceRefresher deviceRefresher,
            JdbcTemplate jdbc,
            @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
                    String schema) {
        this.deviceRefresher = deviceRefresher;
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /**
     * Schema-qualifies a table name with fail-closed identifier validation
     * (Codex 019e8a09 iter-1 must-fix #2 absorb — schema must not be
     * hardcoded; pattern mirrors {@link DiffCacheService}'s qualified()
     * helper).
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
     * Backfill the diff cache for an explicit list of devices in one
     * tenant. Each device runs in its own {@code REQUIRES_NEW} via the
     * proxied refresher.
     */
    public DiffCacheBackfillResult backfillBatch(
            UUID tenantId, DiffType type, List<UUID> deviceIds) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deviceIds, "deviceIds");
        long start = System.nanoTime();
        long checked = 0L;
        long changed = 0L;
        long unchanged = 0L;
        long errors = 0L;
        for (UUID deviceId : deviceIds) {
            checked++;
            try {
                boolean wrote = deviceRefresher.refreshDevice(tenantId, deviceId, type);
                if (wrote) {
                    changed++;
                } else {
                    unchanged++;
                }
            } catch (RuntimeException ex) {
                errors++;
                // Codex 019e8a09 iter-1 must-fix #3 absorb: never log
                // ex.getMessage() — error messages can carry raw payload
                // fragments / SQL details / private data. errorClass +
                // tenant/device/type is enough for an operator to
                // correlate; full diagnostics belong behind a DEBUG flag
                // that operators flip per-incident.
                log.warn("DiffCache backfill failed tenant={} device={} type={} errorClass={}",
                        tenantId, deviceId, type, ex.getClass().getSimpleName());
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new DiffCacheBackfillResult(checked, changed, unchanged, errors, elapsedMs);
    }

    /**
     * Backfill all devices in a tenant for the given type. Pages through
     * {@code endpoint_devices} so the worker batch size cap stays explicit
     * (no streaming of unbounded result sets into memory).
     */
    public DiffCacheBackfillResult backfillTenant(
            UUID tenantId, DiffType type, int pageSize) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("pageSize out of range [1, 5000]: " + pageSize);
        }
        DiffCacheBackfillResult acc = DiffCacheBackfillResult.empty();
        int offset = 0;
        String devicesTable = qualified("endpoint_devices");
        while (true) {
            // Faz 21.1 PR2b-iii canonical effective-org filter (Codex 019e8cd4
            // AGREE). Pass orgId twice: canonical match + legacy null path.
            List<UUID> page = jdbc.query(
                    "SELECT id FROM " + devicesTable + " "
                    + "WHERE (org_id = ? OR (org_id IS NULL AND tenant_id = ?)) "
                    + "ORDER BY id "
                    + "LIMIT ? OFFSET ?",
                    (rs, i) -> (UUID) rs.getObject("id"),
                    tenantId, tenantId, pageSize, offset);
            if (page.isEmpty()) {
                break;
            }
            DiffCacheBackfillResult batch = backfillBatch(tenantId, type, page);
            acc = acc.plus(batch);
            if (page.size() < pageSize) {
                break;
            }
            offset += page.size();
        }
        return acc;
    }
}
