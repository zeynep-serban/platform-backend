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
 * Faz 21.1 Cleanup C4 A2 slice-3 regression guard — V40 hardware_inventory org
 * expansion (third leaf family; first with TWO detail tables). V40 org-expands
 * endpoint_hardware_inventory_snapshots (+ details _disks + _network_interfaces)
 * and flips all 3 tenant-composite FKs to org-composite.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V40HardwareInventoryOrgExpansionPostgresIntegrationTest {

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
            {"hw_inv_disk_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hardware_inventory_snapshots(id, org_id) ON DELETE CASCADE"},
            {"hw_inv_ni_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hardware_inventory_snapshots(id, org_id) ON DELETE CASCADE"},
            {"hw_inv_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allThreeFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            Boolean v = jdbc.queryForObject(
                    "SELECT convalidated FROM pg_constraint WHERE conname = ? AND contype = 'f'", Boolean.class, fk[0]);
            assertThat(v).as("%s validated", fk[0]).isTrue();
            String def = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, fk[0]);
            assertThat(def).as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{"fk_endpoint_hardware_inventory_disks_snapshot",
                "fk_endpoint_hardware_inventory_network_interfaces_snapshot",
                "fk_endpoint_hardware_inventory_snapshots_device"}) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'f'", Long.class, old);
            assertThat(n).as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void allThreeTablesHaveOrgNotNullCheck_andSnapshotsHaveIdOrgUnique() {
        for (String con : new String[]{"endpoint_hw_inv_snap_org_id_not_null",
                "endpoint_hw_inv_disk_org_id_not_null", "endpoint_hw_inv_ni_org_id_not_null"}) {
            Boolean v = jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname = ? AND contype = 'c'", Boolean.class, con);
            assertThat(v).as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'endpoint_hw_inv_snap_id_org_id_key' AND contype='u'", String.class))
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
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + SCHEMA + ".endpoint_hardware_inventory_snapshots WHERE id = ?", UUID.class, snapId))
                .isEqualTo(org);
    }

    @Test
    void crossOrgDiskInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertDisk(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgNetworkInterfaceInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertNetworkInterface(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesSnapshotDisksAndNetworkInterfaces() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertDisk(org, org, snapId);
        insertNetworkInterface(org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id = ?", device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_hardware_inventory_snapshots WHERE id = ?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_hardware_inventory_disks WHERE snapshot_id = ?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".endpoint_hardware_inventory_network_interfaces WHERE snapshot_id = ?", Long.class, snapId)).isEqualTo(0L);
    }

    @Test
    void explicitOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID tenant = UUID.randomUUID(), otherOrg = UUID.randomUUID();
        UUID device = seedDevice(otherOrg); // device under otherOrg → device FK passes, isolates match CHECK
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_hardware_inventory_snapshots DISABLE TRIGGER USER");
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
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, ?, ?)",
                id, tenant, org, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertSnapshotNoOrg(UUID id, UUID tenant, UUID device) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, ?, ?)",
                id, tenant, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertDisk(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hardware_inventory_disks "
                        + "(id, snapshot_id, tenant_id, org_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, tenant, org);
    }

    private void insertNetworkInterface(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hardware_inventory_network_interfaces "
                        + "(id, snapshot_id, tenant_id, org_id) VALUES (?, ?, ?, ?)",
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
