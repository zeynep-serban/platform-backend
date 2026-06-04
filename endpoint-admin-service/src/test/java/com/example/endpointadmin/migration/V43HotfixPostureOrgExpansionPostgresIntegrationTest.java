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
 * Faz 21.1 Cleanup C4 A2 slice-6 regression guard — V43 hotfix_posture org
 * expansion (sixth/last leaf family; GRANDCHILD variant). 5 tables, 5 FK flips,
 * 2 FK parents with UNIQUE(id, org_id): snapshots AND pending. The grandchild
 * edge pending_kbs→pending is the structural novelty vs the prior 5 families.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V43HotfixPostureOrgExpansionPostgresIntegrationTest {

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

    private static final String SNAP = SCHEMA + ".endpoint_hotfix_posture_snapshots";
    private static final String INSTALLED = SCHEMA + ".endpoint_hotfix_posture_installed";
    private static final String PENDING = SCHEMA + ".endpoint_hotfix_posture_pending";
    private static final String PENDING_KBS = SCHEMA + ".endpoint_hotfix_posture_pending_kbs";
    private static final String PENDING_CATS = SCHEMA + ".endpoint_hotfix_posture_pending_categories";

    private static final String[][] ORG_FKS = {
            {"hfp_installed_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hotfix_posture_snapshots(id, org_id) ON DELETE CASCADE"},
            {"hfp_pending_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hotfix_posture_snapshots(id, org_id) ON DELETE CASCADE"},
            {"hfp_pending_cats_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hotfix_posture_snapshots(id, org_id) ON DELETE CASCADE"},
            {"hfp_pending_kbs_pending_org_fk",
             "FOREIGN KEY (pending_id, org_id) REFERENCES " + SCHEMA + ".endpoint_hotfix_posture_pending(id, org_id) ON DELETE CASCADE"},
            {"hfp_snap_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"}
    };

    private static final String[] OLD_TENANT_FKS = {
            "fk_endpoint_hotfix_post_installed_snapshot",
            "fk_endpoint_hotfix_post_pending_snapshot",
            "fk_endpoint_hotfix_post_pending_cats_snapshot",
            "fk_endpoint_hotfix_post_pending_kbs_pending",
            "fk_endpoint_hotfix_post_snap_device"
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allFiveFksFlippedToOrgCompositeAndValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : OLD_TENANT_FKS) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void allFiveTablesHaveValidatedOrgChecks_andBothFkParentsHaveIdOrgUnique() {
        for (String con : new String[]{
                "endpoint_hfp_snap_org_id_match", "endpoint_hfp_snap_org_id_not_null",
                "endpoint_hfp_installed_org_id_match", "endpoint_hfp_installed_org_id_not_null",
                "endpoint_hfp_pending_org_id_match", "endpoint_hfp_pending_org_id_not_null",
                "endpoint_hfp_pending_kbs_org_id_match", "endpoint_hfp_pending_kbs_org_id_not_null",
                "endpoint_hfp_pending_cats_org_id_match", "endpoint_hfp_pending_cats_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        // BOTH FK parents (snapshots AND pending — grandchild parent) carry UNIQUE(id, org_id).
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_hfp_snap_id_org_id_key' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (id, org_id)");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='endpoint_hfp_pending_id_org_id_key' AND contype='u'", String.class))
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
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + SNAP + " WHERE id=?", UUID.class, snapId)).isEqualTo(org);
    }

    @Test
    void crossOrgInstalledInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertInstalled(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgPendingInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertPending(UUID.randomUUID(), orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgPendingCategoryInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertPendingCategory(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    /** GRANDCHILD edge — the structural novelty of this family. */
    @Test
    void crossOrgPendingKbInsert_isRejectedByPendingOrgFk_23503() {
        UUID org = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        UUID pendingId = UUID.randomUUID();
        insertPending(pendingId, org, org, snapId);
        assertThatThrownBy(() -> insertPendingKb(orgB, orgB, pendingId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    /** 2-level cascade through pending: device → snapshots → pending → pending_kbs. */
    @Test
    void deviceDelete_cascadesAllFiveTablesIncludingGrandchild() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertInstalled(org, org, snapId);
        UUID pendingId = UUID.randomUUID();
        insertPending(pendingId, org, org, snapId);
        insertPendingKb(org, org, pendingId);
        insertPendingCategory(org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id=?", device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SNAP + " WHERE id=?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + INSTALLED + " WHERE snapshot_id=?", Long.class, snapId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + PENDING + " WHERE id=?", Long.class, pendingId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + PENDING_KBS + " WHERE pending_id=?", Long.class, pendingId)).isEqualTo(0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + PENDING_CATS + " WHERE snapshot_id=?", Long.class, snapId)).isEqualTo(0L);
    }

    @Test
    void explicitOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        UUID tenant = UUID.randomUUID(), otherOrg = UUID.randomUUID();
        UUID device = seedDevice(otherOrg);
        jdbc.execute("ALTER TABLE " + SNAP + " DISABLE TRIGGER USER");
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
        jdbc.update("INSERT INTO " + SNAP + " "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, probe_complete, "
                        + " installed_count, max_installed, installed_truncated, pending_total_count, max_pending, pending_truncated, "
                        + " installed_source_used, pending_source_used, health_source_used, payload_hash_sha256, "
                        + " wua_service_state, bits_service_state, collected_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, 0, 0, false, 0, 0, false, "
                        + " 'none', 'none', 'none', ?, 'UNKNOWN', 'UNKNOWN', ?)",
                id, tenant, org, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertSnapshotNoOrg(UUID id, UUID tenant, UUID device) {
        jdbc.update("INSERT INTO " + SNAP + " "
                        + "(id, tenant_id, device_id, schema_version, supported, probe_complete, "
                        + " installed_count, max_installed, installed_truncated, pending_total_count, max_pending, pending_truncated, "
                        + " installed_source_used, pending_source_used, health_source_used, payload_hash_sha256, "
                        + " wua_service_state, bits_service_state, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 0, 0, false, 0, 0, false, "
                        + " 'none', 'none', 'none', ?, 'UNKNOWN', 'UNKNOWN', ?)",
                id, tenant, device, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertInstalled(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + INSTALLED + " "
                        + "(id, snapshot_id, tenant_id, org_id, kb_id, row_ordinal) VALUES (?, ?, ?, ?, 'KB1234567', 0)",
                UUID.randomUUID(), snapshotId, tenant, org);
    }

    private void insertPending(UUID id, UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + PENDING + " "
                        + "(id, snapshot_id, tenant_id, org_id, primary_category, severity, row_ordinal) "
                        + "VALUES (?, ?, ?, ?, 'SECURITY', 'CRITICAL', 0)",
                id, snapshotId, tenant, org);
    }

    private void insertPendingKb(UUID tenant, UUID org, UUID pendingId) {
        jdbc.update("INSERT INTO " + PENDING_KBS + " "
                        + "(id, pending_id, tenant_id, org_id, kb_id, row_ordinal) VALUES (?, ?, ?, ?, 'KB1234567', 0)",
                UUID.randomUUID(), pendingId, tenant, org);
    }

    private void insertPendingCategory(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + PENDING_CATS + " "
                        + "(id, snapshot_id, tenant_id, org_id, category, cnt, row_ordinal) "
                        + "VALUES (?, ?, ?, ?, 'SECURITY', 0, 0)",
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
