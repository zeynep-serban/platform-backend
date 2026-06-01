package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDiagnosticsProbeError;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
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
 * BE — race-safe write path for the append-only agent self-diagnostics
 * snapshot (Faz 22.5, AG-038-be ingest). Mirrors the AG-037 V22 hotfix-
 * posture pattern EXACTLY: targetless {@code ON CONFLICT DO NOTHING} on
 * the snapshot scalar row + same-transaction native-insert of child
 * probe-error rows.
 *
 * <h3>PostgreSQL (CI-authoritative + production)</h3>
 *
 * <p>Native targetless {@code INSERT ... ON CONFLICT DO NOTHING} on the
 * snapshot row catches BOTH partial source-command-result-id UNIQUE and
 * full (tenant, device, hash) UNIQUE race-cleanly. ANY other violation
 * — schema_version, payload_hash regex, lastError triad CHECK, code regex
 * for both root + child, summary length / CRLF — propagates as
 * {@code DataIntegrityViolationException} and rolls back the whole ingest
 * transaction.
 *
 * <h3>Non-PostgreSQL (H2 unit/slice tests)</h3>
 *
 * <p>H2 cannot reliably reproduce partial UNIQUE indexes or ON CONFLICT
 * grammar; the H2 slice persists the managed entity graph through the
 * {@link EntityManager} and relies on the service pre-probe for
 * idempotency. Every constraint violation still propagates.
 */
public class EndpointDiagnosticsSnapshotRepositoryImpl
        implements EndpointDiagnosticsSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointDiagnosticsSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE = "endpoint_diagnostics_snapshots";
    private static final String PROBE_ERRORS_TABLE = "endpoint_diagnostics_probe_errors";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertDiagnosticsSnapshotOnConflictDoNothing(
            EndpointDiagnosticsSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        // Non-Postgres (H2 slice): persist the managed entity graph through
        // Hibernate. The service pre-probe is the idempotency guard.
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(EndpointDiagnosticsSnapshot snapshot) {
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
                     agent_version, config_hash,
                     last_poll_latency_ms,
                     backend_dns_reachable, backend_tls_valid,
                     last_error_occurred_at, last_error_code, last_error_summary,
                     probe_duration_ms, payload_hash_sha256,
                     collected_at, created_at)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :agentVersion, :configHash,
                     :lastPollLatencyMs,
                     :backendDnsReachable, :backendTlsValid,
                     :lastErrorOccurredAt, :lastErrorCode, :lastErrorSummary,
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
                .setParameter("agentVersion", snapshot.getAgentVersion())
                .setParameter("configHash", snapshot.getConfigHash())
                .setParameter("lastPollLatencyMs", snapshot.getLastPollLatencyMs())
                .setParameter("backendDnsReachable", snapshot.getBackendDnsReachable())
                .setParameter("backendTlsValid", snapshot.getBackendTlsValid())
                .setParameter("lastErrorOccurredAt", snapshot.getLastErrorOccurredAt())
                .setParameter("lastErrorCode", snapshot.getLastErrorCode())
                .setParameter("lastErrorSummary", snapshot.getLastErrorSummary())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
                .setParameter("collectedAt", snapshot.getCollectedAt())
                .setParameter("createdAt", createdAt)
                .executeUpdate();

        if (inserted == 0) {
            // Targetless ON CONFLICT hit either UNIQUE — caller MUST
            // re-lookup the winner.
            return null;
        }

        insertProbeErrorsPostgres(snapshot, id);
        return id;
    }

    private void insertProbeErrorsPostgres(
            EndpointDiagnosticsSnapshot snapshot, UUID snapshotId) {
        List<EndpointDiagnosticsProbeError> errors = snapshot.getProbeErrors();
        if (errors == null || errors.isEmpty()) {
            return;
        }
        String table = qualified(PROBE_ERRORS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, code, summary)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :code, :summary)
                """.formatted(table);
        for (EndpointDiagnosticsProbeError err : errors) {
            UUID rowId = err.getId() != null ? err.getId() : UUID.randomUUID();
            err.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", err.getRowOrdinal())
                    .setParameter("code", err.getCode())
                    .setParameter("summary", err.getSummary())
                    .executeUpdate();
        }
    }

    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) {
            return cached;
        }
        boolean detected = false;
        try {
            org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
            String product = session.doReturningWork(connection ->
                    connection.getMetaData().getDatabaseProductName());
            if (product != null) {
                detected = product.toLowerCase(Locale.ROOT).contains("postgres");
            }
        } catch (RuntimeException ex) {
            log.debug("AG-038-be could not resolve database dialect — defaulting to non-Postgres", ex);
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
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
