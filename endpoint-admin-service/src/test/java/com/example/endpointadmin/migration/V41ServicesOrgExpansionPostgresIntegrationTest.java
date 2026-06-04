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
 * Faz 21.1 Cleanup C4 A2 slice-4 regression guard — V41 services org expansion
 * (fourth leaf family; snapshots + entries + probe_errors, 3 FK flips).
 * Mirrors V40 hardware_inventory.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V41ServicesOrgExpansionPostgresIntegrationTest {

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

    private static final String[][] ORG_FKS = {
            {"svcs_entry_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_services_snapshots(id, org_id) ON DELETE CASCADE"},
            {"svcs_pe_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_services_snapshots(id, org_id) ON DELETE CASCADE"},
            {"svcs_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allThreeFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{"svcs_ent_snapshot_fk", "svcs_pe_snapshot_fk", "svcs_snap_device_fk"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void allThreeTablesHaveValidatedOrgChecks_andSnapshotsHaveIdOrgUnique() {
        // match + non-null CHECK validated on all 3 tables (Codex 019e942f hardening).
        for (String con : new String[]{
                "endpoint_svcs_snap_org_id_match", "endpoint_svcs_snap_org_id_not_null",
                "endpoint_svcs_entry_org_id_match", "endpoint_svcs_entry_org_id_not_null",
                "endpoint_svcs_pe_org_id_match", "endpoint_svcs_pe_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_svcs_snap_id_org_id_key' AND contype='u'", String.class))
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
    void legacyWriter_orgIdOmitted_filledByTriggerToTenant() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshotNoOrg(snapId, org, device);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + SCHEMA + ".endpoint_services_snapshots WHERE id=?", UUID.class, snapId)).isEqualTo(org);
    }

    @Test
    void crossOrgEntryInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertEntry(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgProbeErrorInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertProbeError(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesSnapshotEntriesAndProbeErrors() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertEntry(org, org, snapId);
        insertProbeError(org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id=?", device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_services_snapshots WHERE id=?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_services_entries WHERE snapshot_id=?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_services_probe_errors WHERE snapshot_id=?", Long.class, snapId)).isEqualTo(0L);
    }

    @Test
    void explicitOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID tenant = UUID.randomUUID(), otherOrg = UUID.randomUUID();
        UUID device = seedDevice(otherOrg);
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_services_snapshots DISABLE TRIGGER USER");
        assertThatThrownBy(() -> insertSnapshot(UUID.randomUUID(), tenant, otherOrg, device))
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
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_services_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, probe_complete, probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, 10, ?, ?)",
                id, tenant, org, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertSnapshotNoOrg(UUID id, UUID tenant, UUID device) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_services_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, probe_complete, probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 10, ?, ?)",
                id, tenant, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertEntry(UUID tenant, UUID org, UUID snapshotId) {
        // name/state/startup_mode are enum-CHECK'd in V24.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_services_entries "
                        + "(id, snapshot_id, tenant_id, org_id, row_ordinal, name, present, state, startup_mode) "
                        + "VALUES (?, ?, ?, ?, 0, 'WinDefend', true, 'RUNNING', 'AUTO')",
                UUID.randomUUID(), snapshotId, tenant, org);
    }

    private void insertProbeError(UUID tenant, UUID org, UUID snapshotId) {
        // code is enum-CHECK'd in V24.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_services_probe_errors "
                        + "(id, snapshot_id, tenant_id, org_id, row_ordinal, code) VALUES (?, ?, ?, ?, 0, 'SCM_UNAVAILABLE')",
                UUID.randomUUID(), snapshotId, tenant, org);
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
