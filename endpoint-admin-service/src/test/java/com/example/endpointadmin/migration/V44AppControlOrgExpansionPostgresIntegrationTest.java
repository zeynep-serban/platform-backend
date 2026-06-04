package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 Cleanup C4 step-4 regression guard — V44 app_control org expansion
 * (ORG-DONE-PARENT variant). Parent endpoint_app_control_snapshots was already
 * org-foundation-done (V29/V30/V36); this slice adds its UNIQUE(id, org_id) +
 * flips its device FK, and gives the detail probe_errors full org machinery +
 * its FK flip. 2 FK flips total.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V44AppControlOrgExpansionPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String HEX64 = "a".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    private static final String SNAP = SCHEMA + ".endpoint_app_control_snapshots";
    private static final String PE = SCHEMA + ".endpoint_app_control_probe_errors";

    private static final String[][] ORG_FKS = {
            {"ac_pe_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_app_control_snapshots(id, org_id) ON DELETE CASCADE"},
            {"ac_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bothFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{"ac_pe_snapshot_fk", "ac_snap_device_fk"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void detailHasValidatedOrgChecks_andParentHasIdOrgUnique() {
        for (String con : new String[]{"endpoint_ac_pe_org_id_match", "endpoint_ac_pe_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ac_snap_id_org_uq' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void crossOrgSnapshotInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA);
        assertThatThrownBy(() -> insertSnapshot(UUID.randomUUID(), orgB, orgB, device))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void legacyWriter_probeErrorOrgIdOmitted_filledByTriggerToTenant() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        UUID peId = UUID.randomUUID();
        insertProbeErrorNoOrg(peId, org, snapId);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + PE + " WHERE id=?", UUID.class, peId)).isEqualTo(org);
    }

    @Test
    void crossOrgProbeErrorInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertProbeError(UUID.randomUUID(), orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesSnapshotAndProbeErrors() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertProbeError(UUID.randomUUID(), org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id=?", device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SNAP + " WHERE id=?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + PE + " WHERE snapshot_id=?", Long.class, snapId)).isEqualTo(0L);
    }

    @Test
    void explicitDetailOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID org = UUID.randomUUID(), tenant = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        // Disable the detail compat trigger so the explicit mismatched org_id is not auto-filled.
        jdbc.execute("ALTER TABLE " + PE + " DISABLE TRIGGER USER");
        // tenant<>org on the detail: match CHECK (org_id = tenant_id) fires 23514 before the FK.
        assertThatThrownBy(() -> insertProbeErrorExplicit(UUID.randomUUID(), tenant, org, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23514"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private UUID seedDevice(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private void insertSnapshot(UUID id, UUID tenant, UUID org, UUID device) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SNAP + " "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, probe_complete, "
                        + " wdac_queryable, app_locker_queryable, wdac_mode, app_locker_exe_rule, app_locker_dll_rule, "
                        + " app_locker_script_rule, app_locker_msi_rule, app_locker_appx_rule, app_locker_app_id_svc_state, "
                        + " app_locker_app_id_svc_startup, probe_duration_ms, payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, true, true, "
                        + " 'UNKNOWN', 'NOT_CONFIGURED', 'NOT_CONFIGURED', 'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                        + " 'NOT_CONFIGURED', 'UNKNOWN', 'AUTO', 0, ?, ?, ?)",
                id, tenant, org, device, HEX64, now, now);
    }

    private void insertProbeError(UUID id, UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + PE + " "
                        + "(id, snapshot_id, tenant_id, org_id, row_ordinal, code) VALUES (?, ?, ?, ?, 0, 'NO_EVIDENCE')",
                id, snapshotId, tenant, org);
    }

    private void insertProbeErrorNoOrg(UUID id, UUID tenant, UUID snapshotId) {
        jdbc.update("INSERT INTO " + PE + " "
                        + "(id, snapshot_id, tenant_id, row_ordinal, code) VALUES (?, ?, ?, 0, 'NO_EVIDENCE')",
                id, snapshotId, tenant);
    }

    /** Explicit org_id (used after trigger disabled) to exercise the match CHECK. */
    private void insertProbeErrorExplicit(UUID id, UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + PE + " "
                        + "(id, snapshot_id, tenant_id, org_id, row_ordinal, code) VALUES (?, ?, ?, ?, 0, 'NO_EVIDENCE')",
                id, snapshotId, tenant, org);
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) return sqlEx.getSQLState();
            cur = cur.getCause();
        }
        return null;
    }
}
