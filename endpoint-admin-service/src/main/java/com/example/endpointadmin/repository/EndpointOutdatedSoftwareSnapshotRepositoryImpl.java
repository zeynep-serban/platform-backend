package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
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
 * BE — race-safe + atomic write path for the append-only outdated-software
 * snapshot (Faz 22.5, AG-036 ingest). Reuses the BE-024 atomicity pattern
 * ({@code EndpointSoftwareInventoryStateHistoryRepositoryImpl}, Codex
 * 019e75fe CRITICAL) so a duplicate command-result is a clean no-op while
 * every other constraint / FK / CHECK breach propagates + rolls back the
 * whole ingest transaction (no broad-catch swallow, no PG rollback-only
 * leak into the audit/commit stage).
 *
 * <h3>PostgreSQL (CI-authoritative + production)</h3>
 *
 * <p>A native {@code INSERT ... ON CONFLICT (source_command_result_id)
 * WHERE source_command_result_id IS NOT NULL DO NOTHING}. The conflict target
 * repeats the V20 partial-index predicate so PG infers the <em>partial</em>
 * unique index {@code uq_endpoint_outdated_software_snapshots_source_cmd_result}.
 * Consequences:
 * <ul>
 *   <li>A duplicate non-null {@code source_command_result_id} → {@code DO
 *       NOTHING} → 0 rows, no exception, transaction stays clean. The method
 *       returns {@code null} so the caller writes NO child package rows.</li>
 *   <li>A {@code NULL} {@code source_command_result_id} (manual/test path) is
 *       outside the partial index, so it never conflicts and always
 *       inserts.</li>
 *   <li>ANY other violation — the {@code schema_version} / {@code upgrade_count}
 *       / {@code max_upgrade} range CHECKs, the {@code payload_hash_sha256}
 *       regex CHECK, the {@code source_used} enum CHECK, the
 *       {@code jsonb_typeof} CHECKs, or the composite device FK — is NOT the
 *       conflict target, so it propagates as a
 *       {@code DataIntegrityViolationException} and rolls the whole ingest
 *       transaction back together with the snapshot + result.</li>
 * </ul>
 *
 * <p>When the scalar row is inserted the implementation then native-inserts
 * each child {@code endpoint_outdated_software_packages} row bound to the
 * assigned snapshot id (so the snapshot + its packages commit / roll back as
 * one unit). A child-row CHECK / composite-FK breach therefore also rolls the
 * whole transaction back.
 *
 * <h3>Non-PostgreSQL (H2 unit/slice tests)</h3>
 *
 * <p>H2 — even in {@code MODE=PostgreSQL} — supports neither partial unique
 * indexes nor the {@code ON CONFLICT ... DO NOTHING} grammar, and the partial
 * unique index does not exist on the Hibernate-generated H2 schema (it lives
 * only in the V20 Flyway migration). The H2 slice has no concurrency, so this
 * path persists the managed entity graph through the {@link EntityManager}
 * (Hibernate assigns the {@code @GeneratedValue} ids + runs {@code @PrePersist}
 * + cascades the child packages); the service-level pre-probe is the
 * idempotency guard there. Every constraint violation still propagates (no
 * swallow). Mirrors the dialect-aware fallback used by
 * {@code EndpointSoftwareInventoryStateHistoryRepositoryImpl}
 * ({@code resolveIsPostgresDialect}) / {@code EndpointComplianceService}
 * (Codex 019e6bdf precedent).
 */
public class EndpointOutdatedSoftwareSnapshotRepositoryImpl
        implements EndpointOutdatedSoftwareSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointOutdatedSoftwareSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE =
            "endpoint_outdated_software_snapshots";

    private static final String PACKAGE_TABLE =
            "endpoint_outdated_software_packages";

    /** Local, config-free mapper: the payload is built from already-sanitized
     *  String / null values, so default serialization is deterministic and
     *  needs no Spring-managed {@code ObjectMapper} bean (which the
     *  {@code @DataJpaTest} slice does not provide). */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertIfNewSourceCommandResult(
            EndpointOutdatedSoftwareSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        // Non-Postgres (H2 slice): no partial index, no concurrency — persist
        // the managed entity graph through Hibernate so @GeneratedValue ids +
        // @PrePersist + the child-package cascade all run. The service
        // pre-probe is the idempotency guard; any constraint violation still
        // propagates (no swallow).
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(
            EndpointOutdatedSoftwareSnapshot snapshot) {
        // The native insert bypasses Hibernate's @GeneratedValue id +
        // @PrePersist created_at/updated_at defaults, so assign all here (the
        // entity is never persisted through the EntityManager on this branch).
        UUID id = snapshot.getId() != null
                ? snapshot.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = snapshot.getCreatedAt() != null
                ? snapshot.getCreatedAt() : now;
        Instant updatedAt = snapshot.getUpdatedAt() != null
                ? snapshot.getUpdatedAt() : now;
        // Reflect the assigned id back onto the entity so the caller can use
        // it (and so the audit event references the persisted id).
        snapshot.setId(id);
        snapshot.setCreatedAt(createdAt);

        String table = qualified(SNAPSHOT_TABLE);
        // The ON CONFLICT clause repeats the V20 partial-index predicate
        // (WHERE source_command_result_id IS NOT NULL) so PG infers the
        // PARTIAL unique index. Without it PG raises "ON CONFLICT does not
        // match any unique/exclusion constraint". CAST(... AS jsonb) binds the
        // redacted_payload / probe_errors text as JSONB; the V20 jsonb_typeof
        // CHECKs still apply.
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, device_id, source_command_result_id,
                     schema_version, supported, probe_complete,
                     upgrade_count, upgrade_truncated, max_upgrade,
                     source_used, probe_duration_ms, payload_hash_sha256,
                     redacted_payload, probe_errors,
                     collected_at, created_at, updated_at, version)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :upgradeCount, :upgradeTruncated, :maxUpgrade,
                     :sourceUsed, :probeDurationMs, :payloadHash,
                     CAST(:redactedPayload AS jsonb), CAST(:probeErrors AS jsonb),
                     :collectedAt, :createdAt, :updatedAt, :version)
                ON CONFLICT (source_command_result_id)
                    WHERE source_command_result_id IS NOT NULL
                    DO NOTHING
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
                .setParameter("upgradeCount", snapshot.getUpgradeCount())
                .setParameter("upgradeTruncated", snapshot.getUpgradeTruncated())
                .setParameter("maxUpgrade", snapshot.getMaxUpgrade())
                .setParameter("sourceUsed", snapshot.getSourceUsed())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
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
            // Duplicate source_command_result_id: clean no-op. The caller must
            // NOT write child package rows.
            return null;
        }

        // Scalar row landed — native-insert each child package row bound to
        // the assigned snapshot id (same transaction as the snapshot, so a
        // child CHECK / composite-FK breach rolls everything back together).
        insertPackagesPostgres(snapshot, id, now);
        return id;
    }

    private void insertPackagesPostgres(
            EndpointOutdatedSoftwareSnapshot snapshot, UUID snapshotId, Instant now) {
        List<EndpointOutdatedSoftwarePackage> packages = snapshot.getPackages();
        if (packages == null || packages.isEmpty()) {
            return;
        }
        String table = qualified(PACKAGE_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, package_id,
                     installed_version, available_version, created_at)
                VALUES
                    (:id, :snapshotId, :tenantId, :packageId,
                     :installedVersion, :availableVersion, :createdAt)
                """.formatted(table);
        for (EndpointOutdatedSoftwarePackage pkg : packages) {
            UUID pkgId = pkg.getId() != null ? pkg.getId() : UUID.randomUUID();
            Instant pkgCreatedAt = pkg.getCreatedAt() != null
                    ? pkg.getCreatedAt() : now;
            entityManager.createNativeQuery(sql)
                    .setParameter("id", pkgId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("packageId", pkg.getPackageId())
                    .setParameter("installedVersion", pkg.getInstalledVersion())
                    .setParameter("availableVersion", pkg.getAvailableVersion())
                    .setParameter("createdAt", pkgCreatedAt)
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
                    "AG-036 outdated-software JSON serialization failed.", ex);
        }
    }

    /**
     * Lazy single-call cached probe (mirrors
     * {@code EndpointSoftwareInventoryStateHistoryRepositoryImpl
     * .resolveIsPostgresDialect()} / {@code EndpointComplianceService}, Codex
     * 019e6bdf). Returns {@code true} only on a genuine Postgres engine; H2 /
     * unknown engines return {@code false} so the portable Hibernate persist
     * path runs. A metadata probe failure defaults to {@code false} (the
     * portable path is always safe).
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
            log.debug("AG-036 could not resolve database dialect — "
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
