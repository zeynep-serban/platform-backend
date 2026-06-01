package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHotfixPostureInstalled;
import com.example.endpointadmin.model.EndpointHotfixPosturePending;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingCategoryCount;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingKb;
import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BE — race-safe + atomic write path for the append-only hotfix posture
 * snapshot (Faz 22.5, AG-037 ingest). Reuses the BE-024/AG-036 atomicity
 * pattern with the AG-037-specific extension to targetless
 * {@code ON CONFLICT DO NOTHING} (Codex 019e81fe iter-3 P1.1) so BOTH
 * legitimate idempotency conflicts (source_command_result_id and
 * (tenant, device, hash)) are caught race-cleanly, while every other
 * CHECK / FK / child UNIQUE violation propagates and rolls back the
 * whole ingest transaction.
 *
 * <h3>PostgreSQL (CI-authoritative + production)</h3>
 *
 * <p>A native targetless {@code INSERT ... ON CONFLICT DO NOTHING} on
 * the snapshot scalar row. Consequences:
 * <ul>
 *   <li>A duplicate {@code source_command_result_id} (partial UNIQUE
 *       conflict, non-null only) → 0 rows, no exception, transaction
 *       stays clean. The method returns {@code null} so the caller
 *       writes NO child rows.</li>
 *   <li>A duplicate {@code (tenant_id, device_id, payload_hash_sha256)}
 *       (full UNIQUE conflict) → same race-clean no-op behavior. The
 *       service re-looks up the winner via
 *       {@code findFirstByTenantIdAndDeviceIdAndPayloadHashSha256...}
 *       and returns it.</li>
 *   <li>A {@code NULL} {@code source_command_result_id} (manual/test
 *       path) is outside the partial UNIQUE so it never conflicts on
 *       that index; it can still conflict on the (tenant, device, hash)
 *       UNIQUE if a same-hash row exists.</li>
 *   <li>ANY other violation — schema_version / count range CHECKs,
 *       payload_hash regex CHECK, sourceUsed enums, jsonb_typeof CHECKs,
 *       composite device FK, notification_level regex, service-state
 *       enums — is NOT the conflict target, so it propagates as a
 *       {@code DataIntegrityViolationException} and rolls the whole
 *       ingest transaction back together with the snapshot + result.</li>
 * </ul>
 *
 * <p>When the scalar row is inserted the implementation then
 * native-inserts each child row bound to the assigned snapshot id (and
 * each {@code pending_kbs} row bound to its parent pending id), all in
 * the same transaction. A child-row CHECK / composite-FK breach
 * therefore also rolls the whole transaction back.
 *
 * <h3>Non-PostgreSQL (H2 unit/slice tests)</h3>
 *
 * <p>H2 — even in {@code MODE=PostgreSQL} — supports neither partial
 * unique indexes nor the {@code ON CONFLICT DO NOTHING} grammar reliably,
 * and the partial unique index does not exist on the Hibernate-generated
 * H2 schema (it lives only in the V22 Flyway migration). The H2 slice
 * has no concurrency, so this path persists the managed entity graph
 * through the {@link EntityManager} (Hibernate assigns the
 * {@code @GeneratedValue} ids + runs {@code @PrePersist} + cascades the
 * three child collections + the pending→kbs grand-child); the service-
 * level pre-probe is the idempotency guard there. Every constraint
 * violation still propagates (no swallow). Mirrors the dialect-aware
 * fallback used by
 * {@link EndpointOutdatedSoftwareSnapshotRepositoryImpl}.
 */
public class EndpointHotfixPostureSnapshotRepositoryImpl
        implements EndpointHotfixPostureSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointHotfixPostureSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE =
            "endpoint_hotfix_posture_snapshots";

    private static final String INSTALLED_TABLE =
            "endpoint_hotfix_posture_installed";

    private static final String PENDING_TABLE =
            "endpoint_hotfix_posture_pending";

    private static final String PENDING_KBS_TABLE =
            "endpoint_hotfix_posture_pending_kbs";

    private static final String PENDING_CATEGORIES_TABLE =
            "endpoint_hotfix_posture_pending_categories";

    /** Local, config-free mapper: the payload is built from already-
     *  sanitized String/null values, so default serialization is
     *  deterministic and needs no Spring-managed {@code ObjectMapper}
     *  bean (which the {@code @DataJpaTest} slice does not provide). */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertHotfixPostureSnapshotOnConflictDoNothing(
            EndpointHotfixPostureSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        // Non-Postgres (H2 slice): no partial index, no concurrency —
        // persist the managed entity graph through Hibernate so
        // @GeneratedValue ids + @PrePersist + the child cascades all run.
        // The service pre-probe is the idempotency guard; any constraint
        // violation still propagates (no swallow).
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(
            EndpointHotfixPostureSnapshot snapshot) {
        // The native insert bypasses Hibernate's @GeneratedValue id +
        // @PrePersist created_at/updated_at defaults, so assign all here
        // (the entity is never persisted through the EntityManager on
        // this branch).
        UUID id = snapshot.getId() != null
                ? snapshot.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = snapshot.getCreatedAt() != null
                ? snapshot.getCreatedAt() : now;
        Instant updatedAt = snapshot.getUpdatedAt() != null
                ? snapshot.getUpdatedAt() : now;
        // Reflect the assigned id back onto the entity so the caller can
        // use it (and so the audit event references the persisted id).
        snapshot.setId(id);
        snapshot.setCreatedAt(createdAt);

        String table = qualified(SNAPSHOT_TABLE);
        // Targetless ON CONFLICT DO NOTHING — Codex 019e81fe iter-3
        // P1.1. Catches BOTH partial source_command_result_id UNIQUE
        // and full (tenant, device, hash) UNIQUE transaction-cleanly.
        // CAST(... AS jsonb) binds the redacted_payload / probe_errors
        // text as JSONB; the V22 jsonb_typeof CHECKs still apply.
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, device_id, source_command_result_id,
                     schema_version, supported, probe_complete,
                     installed_count, max_installed, installed_truncated,
                     pending_total_count, max_pending, pending_truncated,
                     installed_source_used, pending_source_used,
                     health_source_used, probe_duration_ms,
                     payload_hash_sha256,
                     wua_service_state, bits_service_state,
                     last_detect_at, last_install_at,
                     auto_update_policy_enabled, auto_update_effective_enabled,
                     notification_level,
                     redacted_payload, probe_errors,
                     collected_at, created_at, updated_at, version)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :installedCount, :maxInstalled, :installedTruncated,
                     :pendingTotalCount, :maxPending, :pendingTruncated,
                     :installedSourceUsed, :pendingSourceUsed,
                     :healthSourceUsed, :probeDurationMs,
                     :payloadHash,
                     :wuaServiceState, :bitsServiceState,
                     :lastDetectAt, :lastInstallAt,
                     :autoUpdatePolicyEnabled, :autoUpdateEffectiveEnabled,
                     :notificationLevel,
                     CAST(:redactedPayload AS jsonb),
                     CAST(:probeErrors AS jsonb),
                     :collectedAt, :createdAt, :updatedAt, :version)
                ON CONFLICT DO NOTHING
                """.formatted(table);

        int inserted = entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("tenantId", snapshot.getTenantId())
                .setParameter("deviceId", snapshot.getDeviceId())
                .setParameter("sourceCommandResultId",
                        snapshot.getSourceCommandResultId())
                .setParameter("schemaVersion", snapshot.getSchemaVersion())
                .setParameter("supported", snapshot.getSupported())
                .setParameter("probeComplete", snapshot.getProbeComplete())
                .setParameter("installedCount", snapshot.getInstalledCount())
                .setParameter("maxInstalled", snapshot.getMaxInstalled())
                .setParameter("installedTruncated", snapshot.getInstalledTruncated())
                .setParameter("pendingTotalCount", snapshot.getPendingTotalCount())
                .setParameter("maxPending", snapshot.getMaxPending())
                .setParameter("pendingTruncated", snapshot.getPendingTruncated())
                .setParameter("installedSourceUsed", snapshot.getInstalledSourceUsed())
                .setParameter("pendingSourceUsed", snapshot.getPendingSourceUsed())
                .setParameter("healthSourceUsed", snapshot.getHealthSourceUsed())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
                .setParameter("wuaServiceState", snapshot.getWuaServiceState())
                .setParameter("bitsServiceState", snapshot.getBitsServiceState())
                .setParameter("lastDetectAt", snapshot.getLastDetectAt())
                .setParameter("lastInstallAt", snapshot.getLastInstallAt())
                .setParameter("autoUpdatePolicyEnabled", snapshot.getAutoUpdatePolicyEnabled())
                .setParameter("autoUpdateEffectiveEnabled", snapshot.getAutoUpdateEffectiveEnabled())
                .setParameter("notificationLevel", snapshot.getNotificationLevel())
                .setParameter("redactedPayload",
                        serializeJson(snapshot.getRedactedPayload()))
                .setParameter("probeErrors",
                        serializeJson(snapshot.getProbeErrors()))
                .setParameter("collectedAt", snapshot.getCollectedAt())
                .setParameter("createdAt", createdAt)
                .setParameter("updatedAt", updatedAt)
                .setParameter("version", 0L)
                .executeUpdate();

        if (inserted == 0) {
            // Targetless ON CONFLICT DO NOTHING hit one of the legitimate
            // UNIQUE indexes (source_command_result_id partial or
            // (tenant, device, hash) full). The caller MUST re-lookup the
            // winner via source-then-hash and MUST NOT write child rows
            // (which would be unbound or worse, bound to a dead snapshot).
            return null;
        }

        // Scalar row landed — native-insert each child row bound to the
        // assigned snapshot id (same transaction, so any child CHECK /
        // composite-FK breach rolls everything back together).
        insertInstalledPostgres(snapshot, id, now);
        insertPendingPostgres(snapshot, id, now);
        insertPendingCategoriesPostgres(snapshot, id, now);
        return id;
    }

    private void insertInstalledPostgres(
            EndpointHotfixPostureSnapshot snapshot, UUID snapshotId, Instant now) {
        List<EndpointHotfixPostureInstalled> installed = snapshot.getInstalledHotfixes();
        if (installed == null || installed.isEmpty()) {
            return;
        }
        String table = qualified(INSTALLED_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, kb_id,
                     installed_on, description, row_ordinal, created_at)
                VALUES
                    (:id, :snapshotId, :tenantId, :kbId,
                     :installedOn, :description, :rowOrdinal, :createdAt)
                """.formatted(table);
        for (EndpointHotfixPostureInstalled row : installed) {
            UUID rowId = row.getId() != null ? row.getId() : UUID.randomUUID();
            Instant rowCreatedAt = row.getCreatedAt() != null
                    ? row.getCreatedAt() : now;
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("kbId", row.getKbId())
                    .setParameter("installedOn", row.getInstalledOn())
                    .setParameter("description", row.getDescription())
                    .setParameter("rowOrdinal", row.getRowOrdinal())
                    .setParameter("createdAt", rowCreatedAt)
                    .executeUpdate();
        }
    }

    private void insertPendingPostgres(
            EndpointHotfixPostureSnapshot snapshot, UUID snapshotId, Instant now) {
        List<EndpointHotfixPosturePending> pendings = snapshot.getPendingUpdates();
        if (pendings == null || pendings.isEmpty()) {
            return;
        }
        String table = qualified(PENDING_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id,
                     primary_category, severity, row_ordinal, created_at)
                VALUES
                    (:id, :snapshotId, :tenantId,
                     :primaryCategory, :severity, :rowOrdinal, :createdAt)
                """.formatted(table);
        for (EndpointHotfixPosturePending pending : pendings) {
            UUID pendingId = pending.getId() != null ? pending.getId() : UUID.randomUUID();
            Instant rowCreatedAt = pending.getCreatedAt() != null
                    ? pending.getCreatedAt() : now;
            entityManager.createNativeQuery(sql)
                    .setParameter("id", pendingId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("primaryCategory", pending.getPrimaryCategory())
                    .setParameter("severity", pending.getSeverity())
                    .setParameter("rowOrdinal", pending.getRowOrdinal())
                    .setParameter("createdAt", rowCreatedAt)
                    .executeUpdate();
            // Reflect the assigned id back so the grand-child binds.
            pending.setId(pendingId);
            insertPendingKbsPostgres(snapshot, pending, pendingId, rowCreatedAt);
        }
    }

    private void insertPendingKbsPostgres(
            EndpointHotfixPostureSnapshot snapshot,
            EndpointHotfixPosturePending pending,
            UUID pendingId, Instant fallbackCreatedAt) {
        List<EndpointHotfixPosturePendingKb> kbs = pending.getKbs();
        if (kbs == null || kbs.isEmpty()) {
            return;
        }
        String table = qualified(PENDING_KBS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, pending_id, tenant_id, kb_id, row_ordinal, created_at)
                VALUES
                    (:id, :pendingId, :tenantId, :kbId, :rowOrdinal, :createdAt)
                """.formatted(table);
        for (EndpointHotfixPosturePendingKb kb : kbs) {
            UUID kbRowId = kb.getId() != null ? kb.getId() : UUID.randomUUID();
            Instant kbCreatedAt = kb.getCreatedAt() != null
                    ? kb.getCreatedAt() : fallbackCreatedAt;
            entityManager.createNativeQuery(sql)
                    .setParameter("id", kbRowId)
                    .setParameter("pendingId", pendingId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("kbId", kb.getKbId())
                    .setParameter("rowOrdinal", kb.getRowOrdinal())
                    .setParameter("createdAt", kbCreatedAt)
                    .executeUpdate();
        }
    }

    private void insertPendingCategoriesPostgres(
            EndpointHotfixPostureSnapshot snapshot, UUID snapshotId, Instant now) {
        List<EndpointHotfixPosturePendingCategoryCount> categories =
                snapshot.getPendingByCategory();
        if (categories == null || categories.isEmpty()) {
            return;
        }
        String table = qualified(PENDING_CATEGORIES_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, category, cnt, row_ordinal, created_at)
                VALUES
                    (:id, :snapshotId, :tenantId, :category, :cnt, :rowOrdinal, :createdAt)
                """.formatted(table);
        for (EndpointHotfixPosturePendingCategoryCount cat : categories) {
            UUID catId = cat.getId() != null ? cat.getId() : UUID.randomUUID();
            Instant rowCreatedAt = cat.getCreatedAt() != null
                    ? cat.getCreatedAt() : now;
            entityManager.createNativeQuery(sql)
                    .setParameter("id", catId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("category", cat.getCategory())
                    .setParameter("cnt", cat.getCnt())
                    .setParameter("rowOrdinal", cat.getRowOrdinal())
                    .setParameter("createdAt", rowCreatedAt)
                    .executeUpdate();
        }
    }

    private String serializeJson(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            // The payload is built from already-sanitized String/primitive
            // values, so this is not expected at runtime.
            throw new IllegalStateException(
                    "AG-037 hotfix posture JSON serialization failed.", ex);
        }
    }

    /**
     * Lazy single-call cached probe. Returns {@code true} only on a
     * genuine Postgres engine; H2 / unknown engines return {@code false}
     * so the portable Hibernate persist path runs. A metadata probe
     * failure defaults to {@code false} (the portable path is always
     * safe).
     */
    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) {
            return cached;
        }
        boolean detected = false;
        try {
            org.hibernate.Session session =
                    entityManager.unwrap(org.hibernate.Session.class);
            String product = session.doReturningWork(connection ->
                    connection.getMetaData().getDatabaseProductName());
            if (product != null) {
                detected = product.toLowerCase(Locale.ROOT).contains("postgres");
            }
        } catch (RuntimeException ex) {
            log.debug("AG-037 could not resolve database dialect — "
                    + "defaulting to non-Postgres", ex);
        }
        isPostgresDialect = detected;
        return detected;
    }

    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) {
            return tableName;
        }
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException(
                    "Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
