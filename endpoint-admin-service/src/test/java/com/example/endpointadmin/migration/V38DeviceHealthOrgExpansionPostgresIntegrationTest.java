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
 * Faz 21.1 Cleanup C4 A2 slice-1 regression guard — V38 device_health org
 * expansion (the C4 pilot leaf family; Codex 019e93a1). V38 org-expands
 * endpoint_device_health_snapshots (+ detail endpoint_device_health_disks) and
 * flips both tenant-composite FKs to org-composite.
 *
 * <p>Asserts:
 * <ol>
 *   <li>both org-composite FKs exist + VALIDATED with the expected
 *       {@code (col, org_id) -> parent(id, org_id) ON DELETE CASCADE} def;</li>
 *   <li>both old tenant-composite FKs are DROPPED;</li>
 *   <li>both tables have a VALIDATED org_id NOT NULL CHECK, and snapshots has
 *       UNIQUE(id, org_id);</li>
 *   <li>a cross-org snapshot insert (device under org A, snapshot org_id = org B)
 *       is REJECTED 23503 by the device org FK;</li>
 *   <li>a legacy writer that omits org_id is filled by the V29 trigger
 *       (org_id = tenant_id) and succeeds;</li>
 *   <li>a cross-org disk insert (snapshot under org A, disk org_id = org B) is
 *       REJECTED 23503 by the snapshot org FK;</li>
 *   <li>deleting the parent device CASCADES snapshot + disks away.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V38DeviceHealthOrgExpansionPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    // V17 CHECKs: payload_hash_sha256 ~ '^[a-f0-9]{64}$', source_used IN ('win32','none').
    private static final String VALID_HASH = "a".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

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

    /** {fk name, expected org-composite def}. */
    private static final String[][] ORG_FKS = {
            {"dev_health_disk_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_device_health_snapshots(id, org_id) ON DELETE CASCADE"},
            {"dev_health_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bothFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            Boolean validated = jdbc.queryForObject(
                    "SELECT con.convalidated FROM pg_constraint con WHERE con.conname = ? AND con.contype = 'f'",
                    Boolean.class, fk[0]);
            assertThat(validated).as("%s must exist + be VALIDATED", fk[0]).isTrue();
            String def = jdbc.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, fk[0]);
            assertThat(def).as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{"fk_endpoint_device_health_disks_snapshot", "fk_endpoint_device_health_snapshots_device"}) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'f'", Long.class, old);
            assertThat(n).as("old tenant FK %s must be DROPPED", old).isEqualTo(0L);
        }
    }

    @Test
    void bothTablesHaveOrgNotNullCheck_andSnapshotsHaveIdOrgUnique() {
        for (String con : new String[]{"endpoint_dev_health_snap_org_id_not_null", "endpoint_dev_health_disk_org_id_not_null"}) {
            Boolean v = jdbc.queryForObject(
                    "SELECT convalidated FROM pg_constraint WHERE conname = ? AND contype = 'c'", Boolean.class, con);
            assertThat(v).as("%s must be VALIDATED", con).isTrue();
        }
        String uq = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'endpoint_dev_health_snap_id_org_id_key' AND contype = 'u'",
                String.class);
        assertThat(uq).isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void crossOrgSnapshotInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA);
        // snapshot claims org B (tenant=org=B, match CHECK ok) but points at
        // org A's device → (device_id, orgB) has no endpoint_devices(id, orgB)
        // parent → device org FK rejects 23503.
        assertThatThrownBy(() -> insertSnapshot(UUID.randomUUID(), orgB, orgB, device))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void legacyWriter_orgIdOmitted_filledByTriggerToTenant() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        // org_id omitted (null) → V29 compat trigger fills it = tenant_id.
        insertSnapshotNoOrg(snapId, org, device);
        UUID orgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA + ".endpoint_device_health_snapshots WHERE id = ?", UUID.class, snapId);
        assertThat(orgId).as("trigger must fill org_id = tenant_id").isEqualTo(org);
    }

    @Test
    void crossOrgDiskInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        // disk claims org B but points at org A's snapshot → (snapshot_id, orgB)
        // has no snapshots(id, orgB) parent → snapshot org FK rejects 23503.
        assertThatThrownBy(() -> insertDisk(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesSnapshotAndDisks() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertDisk(org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id = ?", device);
        Long snaps = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_device_health_snapshots WHERE id = ?", Long.class, snapId);
        Long disks = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_device_health_disks WHERE snapshot_id = ?", Long.class, snapId);
        assertThat(snaps).as("device delete cascades snapshot").isEqualTo(0L);
        assertThat(disks).as("device delete cascades disks (via snapshot)").isEqualTo(0L);
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
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_device_health_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, probe_complete, "
                        + " any_low_disk, fixed_disk_count, fixed_disks_truncated, max_fixed_disks, "
                        + " source_used, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, false, 0, false, 8, 'win32', ?, ?)",
                id, tenant, org, device, VALID_HASH, Timestamp.from(Instant.now()));
    }

    private void insertSnapshotNoOrg(UUID id, UUID tenant, UUID device) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_device_health_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, probe_complete, "
                        + " any_low_disk, fixed_disk_count, fixed_disks_truncated, max_fixed_disks, "
                        + " source_used, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, false, 0, false, 8, 'win32', ?, ?)",
                id, tenant, device, VALID_HASH, Timestamp.from(Instant.now()));
    }

    private void insertDisk(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_device_health_disks "
                        + "(id, snapshot_id, tenant_id, org_id, drive_letter) VALUES (?, ?, ?, ?, 'C:')",
                UUID.randomUUID(), snapshotId, tenant, org);
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
