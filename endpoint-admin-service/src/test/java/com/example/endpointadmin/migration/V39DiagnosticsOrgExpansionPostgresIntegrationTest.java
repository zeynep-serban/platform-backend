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
 * Faz 21.1 Cleanup C4 A2 slice-2 regression guard — V39 diagnostics org
 * expansion (second leaf family; mirrors the V38 device_health pilot). V39
 * org-expands endpoint_diagnostics_snapshots (+ detail _probe_errors) and flips
 * both tenant-composite FKs to org-composite.
 *
 * <p>Asserts: 2 org FKs present+validated; 2 tenant FKs dropped; org non-null
 * CHECKs + snapshots UNIQUE(id, org_id); cross-org snapshot insert 23503;
 * legacy-writer trigger fill; cross-org probe-error insert 23503; device
 * CASCADE delete; and (Codex 019e93cd follow-up) the match CHECK rejects an
 * explicit org_id != tenant_id 23514.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V39DiagnosticsOrgExpansionPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String HEX64 = "a".repeat(64); // payload_hash / config_hash ~ ^[0-9a-f]{64}$

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

    private static final String[][] ORG_FKS = {
            {"diag_pe_snapshot_org_fk",
             "FOREIGN KEY (snapshot_id, org_id) REFERENCES " + SCHEMA + ".endpoint_diagnostics_snapshots(id, org_id) ON DELETE CASCADE"},
            {"diag_snap_device_org_fk",
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
        for (String old : new String[]{"diag_pe_snapshot_fk", "diag_snap_device_fk"}) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = ? AND contype = 'f'", Long.class, old);
            assertThat(n).as("old tenant FK %s must be DROPPED", old).isEqualTo(0L);
        }
    }

    @Test
    void bothTablesHaveOrgNotNullCheck_andSnapshotsHaveIdOrgUnique() {
        for (String con : new String[]{"endpoint_diag_snap_org_id_not_null", "endpoint_diag_pe_org_id_not_null"}) {
            Boolean v = jdbc.queryForObject(
                    "SELECT convalidated FROM pg_constraint WHERE conname = ? AND contype = 'c'", Boolean.class, con);
            assertThat(v).as("%s must be VALIDATED", con).isTrue();
        }
        String uq = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'endpoint_diag_snap_id_org_id_key' AND contype = 'u'",
                String.class);
        assertThat(uq).isEqualTo("UNIQUE (id, org_id)");
    }

    @Test
    void crossOrgSnapshotInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
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
        UUID orgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA + ".endpoint_diagnostics_snapshots WHERE id = ?", UUID.class, snapId);
        assertThat(orgId).as("trigger must fill org_id = tenant_id").isEqualTo(org);
    }

    @Test
    void crossOrgProbeErrorInsert_isRejectedBySnapshotOrgFk_23503() {
        UUID org = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        assertThatThrownBy(() -> insertProbeError(orgB, orgB, snapId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesSnapshotAndProbeErrors() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID snapId = UUID.randomUUID();
        insertSnapshot(snapId, org, org, device);
        insertProbeError(org, org, snapId);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id = ?", device);
        Long snaps = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_diagnostics_snapshots WHERE id = ?", Long.class, snapId);
        Long pes = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".endpoint_diagnostics_probe_errors WHERE snapshot_id = ?", Long.class, snapId);
        assertThat(snaps).isEqualTo(0L);
        assertThat(pes).isEqualTo(0L);
    }

    @Test
    void explicitOrgNotEqualTenant_isRejectedByMatchCheck_23514() {
        // Codex 019e93cd follow-up: the match CHECK (org IS NULL OR =tenant)
        // rejects an explicit org_id != tenant_id. Disable the compat trigger
        // so org_id stays the explicit mismatched value. Terminal assertion.
        UUID tenant = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        // device seeded under otherOrg so the (device_id, org_id=otherOrg) device
        // FK PASSES — isolating the failure to the match CHECK (org != tenant),
        // not the FK (otherwise both 23514 + 23503 fire with undefined order).
        UUID device = seedDevice(otherOrg);
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_diagnostics_snapshots DISABLE TRIGGER USER");
        assertThatThrownBy(() -> insertSnapshot(UUID.randomUUID(), tenant, otherOrg, device))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("org_id != tenant_id must be 23514 check_violation")
                        .isEqualTo("23514"));
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
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_diagnostics_snapshots "
                        + "(id, tenant_id, org_id, device_id, schema_version, supported, probe_complete, "
                        + " agent_version, config_hash, last_poll_latency_ms, backend_dns_reachable, "
                        + " backend_tls_valid, probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, ?, 1, true, true, '1.0.0', ?, 10, true, true, 20, ?, ?)",
                id, tenant, org, device, HEX64, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertSnapshotNoOrg(UUID id, UUID tenant, UUID device) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_diagnostics_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, probe_complete, "
                        + " agent_version, config_hash, last_poll_latency_ms, backend_dns_reachable, "
                        + " backend_tls_valid, probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, '1.0.0', ?, 10, true, true, 20, ?, ?)",
                id, tenant, device, HEX64, HEX64, Timestamp.from(Instant.now()));
    }

    private void insertProbeError(UUID tenant, UUID org, UUID snapshotId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_diagnostics_probe_errors "
                        + "(id, snapshot_id, tenant_id, org_id, row_ordinal, code) "
                        + "VALUES (?, ?, ?, ?, 0, 'ERR_DIAG')",
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
