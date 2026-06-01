package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointServicesEntry;
import com.example.endpointadmin.model.EndpointServicesProbeError;
import com.example.endpointadmin.model.EndpointServicesSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * BE — race-safe write path for the append-only services snapshot
 * (Faz 22.5, AG-039-be). Mirrors AG-038-be
 * {@code EndpointDiagnosticsSnapshotRepositoryImpl} EXACTLY:
 * targetless {@code ON CONFLICT DO NOTHING} catches both partial
 * source_command_result_id UNIQUE + full (tenant, device, hash) UNIQUE
 * race-cleanly.
 *
 * <h3>PostgreSQL (CI-authoritative)</h3>
 *
 * <p>Native targetless INSERT ... ON CONFLICT DO NOTHING. Returns the
 * inserted id; null on no-op (caller MUST re-lookup winner).
 *
 * <h3>Non-PostgreSQL (H2)</h3>
 *
 * <p>Hibernate persist managed entity graph; service pre-probe is the
 * idempotency guard.
 */
public class EndpointServicesSnapshotRepositoryImpl
        implements EndpointServicesSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointServicesSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE = "endpoint_services_snapshots";
    private static final String ENTRIES_TABLE = "endpoint_services_entries";
    private static final String PROBE_ERRORS_TABLE = "endpoint_services_probe_errors";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertServicesSnapshotOnConflictDoNothing(EndpointServicesSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(EndpointServicesSnapshot snapshot) {
        UUID id = snapshot.getId() != null ? snapshot.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = snapshot.getCreatedAt() != null ? snapshot.getCreatedAt() : now;
        snapshot.setId(id);
        snapshot.setCreatedAt(createdAt);

        String table = qualified(SNAPSHOT_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, device_id, source_command_result_id,
                     schema_version, supported, probe_complete,
                     probe_duration_ms, payload_hash_sha256,
                     collected_at, created_at)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :probeDurationMs, :payloadHash,
                     :collectedAt, :createdAt)
                ON CONFLICT DO NOTHING
                """.formatted(table);

        int inserted = entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("tenantId", snapshot.getTenantId())
                .setParameter("deviceId", snapshot.getDeviceId())
                .setParameter("sourceCommandResultId", snapshot.getSourceCommandResultId())
                .setParameter("schemaVersion", snapshot.getSchemaVersion())
                .setParameter("supported", snapshot.getSupported())
                .setParameter("probeComplete", snapshot.getProbeComplete())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
                .setParameter("collectedAt", snapshot.getCollectedAt())
                .setParameter("createdAt", createdAt)
                .executeUpdate();

        if (inserted == 0) {
            return null;
        }

        insertEntriesPostgres(snapshot, id);
        insertProbeErrorsPostgres(snapshot, id);
        return id;
    }

    private void insertEntriesPostgres(EndpointServicesSnapshot snapshot, UUID snapshotId) {
        List<EndpointServicesEntry> entries = snapshot.getServices();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String table = qualified(ENTRIES_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, name, present, state, startup_mode)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :name, :present, :state, :startupMode)
                """.formatted(table);
        for (EndpointServicesEntry e : entries) {
            UUID rowId = e.getId() != null ? e.getId() : UUID.randomUUID();
            e.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", e.getRowOrdinal())
                    .setParameter("name", e.getName())
                    .setParameter("present", e.getPresent())
                    .setParameter("state", e.getState())
                    .setParameter("startupMode", e.getStartupMode())
                    .executeUpdate();
        }
    }

    private void insertProbeErrorsPostgres(EndpointServicesSnapshot snapshot, UUID snapshotId) {
        List<EndpointServicesProbeError> errs = snapshot.getProbeErrors();
        if (errs == null || errs.isEmpty()) {
            return;
        }
        String table = qualified(PROBE_ERRORS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, code, service_name, summary)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :code, :serviceName, :summary)
                """.formatted(table);
        for (EndpointServicesProbeError err : errs) {
            UUID rowId = err.getId() != null ? err.getId() : UUID.randomUUID();
            err.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", err.getRowOrdinal())
                    .setParameter("code", err.getCode())
                    .setParameter("serviceName", err.getServiceName())
                    .setParameter("summary", err.getSummary())
                    .executeUpdate();
        }
    }

    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) return cached;
        boolean detected = false;
        try {
            org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
            String product = session.doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            if (product != null) {
                detected = product.toLowerCase(Locale.ROOT).contains("postgres");
            }
        } catch (RuntimeException ex) {
            log.debug("AG-039-be could not resolve database dialect — defaulting to non-Postgres", ex);
        }
        isPostgresDialect = detected;
        return detected;
    }

    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) return tableName;
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
