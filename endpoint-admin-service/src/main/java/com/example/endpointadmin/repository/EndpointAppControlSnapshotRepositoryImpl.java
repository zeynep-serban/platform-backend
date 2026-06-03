package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAppControlProbeError;
import com.example.endpointadmin.model.EndpointAppControlSnapshot;
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
 * BE — race-safe write path for the append-only app-control snapshot
 * (Faz 22.5, AG-041-be). Mirrors AG-040-be
 * {@code EndpointStartupExposureSnapshotRepositoryImpl} EXACTLY:
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
public class EndpointAppControlSnapshotRepositoryImpl
        implements EndpointAppControlSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointAppControlSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE = "endpoint_app_control_snapshots";
    private static final String PROBE_ERRORS_TABLE = "endpoint_app_control_probe_errors";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertAppControlSnapshotOnConflictDoNothing(EndpointAppControlSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(EndpointAppControlSnapshot snapshot) {
        UUID id = snapshot.getId() != null ? snapshot.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = snapshot.getCreatedAt() != null ? snapshot.getCreatedAt() : now;
        snapshot.setId(id);
        snapshot.setCreatedAt(createdAt);

        String table = qualified(SNAPSHOT_TABLE);
        // Faz 21.1 PR2b-iv AppControl native insert canonical org_id
        // write (Codex 019e8dec slice f non-blocker absorb): set
        // org_id = tenant_id explicitly at the source. V29 trigger
        // would still back-fill if omitted, but PR2b-ii Option A inline
        // direction is to land the canonical shape at INSERT time so
        // the write contract matches what the dual-read COALESCE path
        // expects post-cleanup.
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, org_id, device_id, source_command_result_id,
                     schema_version, supported, probe_complete,
                     wdac_queryable, app_locker_queryable, wdac_mode,
                     wdac_boot_enforcement_present, wdac_active_cip_policy_count,
                     wdac_legacy_sipolicy_present, wdac_multi_policy_mode,
                     app_locker_exe_rule, app_locker_dll_rule, app_locker_script_rule,
                     app_locker_msi_rule, app_locker_appx_rule,
                     app_locker_app_id_svc_state, app_locker_app_id_svc_startup,
                     app_locker_app_id_svc_present,
                     probe_duration_ms, payload_hash_sha256,
                     collected_at, created_at)
                VALUES
                    (:id, :tenantId, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :wdacQueryable, :appLockerQueryable, :wdacMode,
                     :wdacBoot, :wdacCipCount,
                     :wdacLegacySip, :wdacMulti,
                     :appExe, :appDll, :appScript,
                     :appMsi, :appAppx,
                     :appIdState, :appIdStartup,
                     :appIdPresent,
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
                .setParameter("wdacQueryable", snapshot.getWdacQueryable())
                .setParameter("appLockerQueryable", snapshot.getAppLockerQueryable())
                .setParameter("wdacMode", snapshot.getWdacMode())
                .setParameter("wdacBoot", snapshot.getWdacBootEnforcementPresent())
                .setParameter("wdacCipCount", snapshot.getWdacActiveCipPolicyCount())
                .setParameter("wdacLegacySip", snapshot.getWdacLegacySipolicyPresent())
                .setParameter("wdacMulti", snapshot.getWdacMultiPolicyMode())
                .setParameter("appExe", snapshot.getAppLockerExeRule())
                .setParameter("appDll", snapshot.getAppLockerDllRule())
                .setParameter("appScript", snapshot.getAppLockerScriptRule())
                .setParameter("appMsi", snapshot.getAppLockerMsiRule())
                .setParameter("appAppx", snapshot.getAppLockerAppxRule())
                .setParameter("appIdState", snapshot.getAppLockerAppIdSvcState())
                .setParameter("appIdStartup", snapshot.getAppLockerAppIdSvcStartup())
                .setParameter("appIdPresent", snapshot.getAppLockerAppIdSvcPresent())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
                .setParameter("collectedAt", snapshot.getCollectedAt())
                .setParameter("createdAt", createdAt)
                .executeUpdate();

        if (inserted == 0) {
            return null;
        }

        insertProbeErrorsPostgres(snapshot, id);
        return id;
    }

    private void insertProbeErrorsPostgres(EndpointAppControlSnapshot snapshot, UUID snapshotId) {
        List<EndpointAppControlProbeError> errs = snapshot.getProbeErrors();
        if (errs == null || errs.isEmpty()) {
            return;
        }
        String table = qualified(PROBE_ERRORS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, code, source, summary)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :code, :source, :summary)
                """.formatted(table);
        for (EndpointAppControlProbeError err : errs) {
            UUID rowId = err.getId() != null ? err.getId() : UUID.randomUUID();
            err.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", err.getRowOrdinal())
                    .setParameter("code", err.getCode())
                    .setParameter("source", err.getSource())
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
            log.debug("AG-041-be could not resolve database dialect — defaulting to non-Postgres", ex);
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
