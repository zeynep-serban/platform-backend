package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointHardwareInventorySnapshotRepository;
import jakarta.persistence.EntityManager;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-022 — PostgreSQL-only migration + tenant-integrity integration
 * tests for {@code V13__endpoint_hardware_inventory.sql} (Faz 22.5).
 *
 * <p>Codex {@code 019e7007} iter-3 absorb: the H2 {@code @DataJpaTest}
 * slice cannot exercise parts that depend on real Postgres semantics:
 *
 * <ul>
 *   <li>Composite-FK enforcement on {@code (device_id, tenant_id)} +
 *       {@code (snapshot_id, tenant_id)} — H2 silently accepts
 *       cross-tenant inserts;</li>
 *   <li>Partial UNIQUE on {@code source_command_result_id} —
 *       H2 partial-index support is unreliable;</li>
 *   <li>DB CHECK regex on {@code payload_hash_sha256} and
 *       {@code mac_address} — H2 regex semantics diverge;</li>
 *   <li>{@code jsonb_typeof} CHECK constraints on {@code redacted_payload}
 *       (object) / {@code probe_errors} (array) / {@code ip_addresses}
 *       (array);</li>
 *   <li>{@code ON DELETE CASCADE} chain (device → snapshot → disks/NICs)
 *       + {@code ON DELETE SET NULL} on {@code source_command_result_id}.</li>
 * </ul>
 *
 * <p>Mirrors {@code EndpointInstallAuditPostgresIntegrationTest} setup:
 * PG 16 Testcontainer + Flyway enabled + {@code ddl-auto=validate} +
 * {@code public} schema pinned.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointHardwareInventoryPostgresIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> "public");
    }

    @Autowired
    private EndpointHardwareInventorySnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private static final String VALID_HASH = "a".repeat(64);

    // ──────────────────────────────────────────────────────────────────
    // Flyway / schema validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void flywayLiftsSchemaToV13AndHibernateValidatesAgainstIt() {
        // ddl-auto=validate context start is itself the assertion:
        // every column in the 3 hardware inventory entities must line
        // up with V13 or Spring refuses to bring the test context up.
        assertThat(snapshotRepository).isNotNull();
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void v13RegistersExpectedConstraintsAndIndexes() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public.endpoint_hardware_inventory_snapshots'::regclass "
                        + "AND contype = 'c'",
                String.class);
        // Sanity that the named CHECK constraints exist (the DB
        // engine enforces them by name in violation messages).
        assertThat(checks).contains(
                "ck_endpoint_hardware_inventory_snapshots_hash_format",
                "ck_endpoint_hardware_inventory_snapshots_payload_shape",
                "ck_endpoint_hardware_inventory_snapshots_probe_errors_shape",
                "ck_endpoint_hardware_inventory_snapshots_schema_version_range",
                "ck_endpoint_hardware_inventory_snapshots_cpu_cores_range");

        List<String> partialUniques = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = 'endpoint_hardware_inventory_snapshots' "
                        + "AND indexname = 'uq_endpoint_hardware_inventory_snapshots_source_cmd_result'",
                String.class);
        assertThat(partialUniques).hasSize(1);

        List<String> latestIndex = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = 'endpoint_hardware_inventory_snapshots' "
                        + "AND indexname = 'idx_endpoint_hardware_inventory_snapshots_tenant_device_time'",
                String.class);
        assertThat(latestIndex).hasSize(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // DB CHECK constraint violations
    // ──────────────────────────────────────────────────────────────────

    @Test
    void invalidHashRegexRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " payload_hash_sha256, redacted_payload, probe_errors, "
                        + " collected_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                snapshotId, tenantId, deviceId, 1, true,
                "NOT_A_VALID_SHA256_HEX_VALUE",
                "{}", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_hardware_inventory_snapshots_hash_format");
    }

    @Test
    void redactedPayloadNonObjectRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " payload_hash_sha256, redacted_payload, probe_errors, "
                        + " collected_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, deviceId, 1, true,
                VALID_HASH,
                "[\"not\",\"an\",\"object\"]", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_hardware_inventory_snapshots_payload_shape");
    }

    @Test
    void negativeRamTotalBytesRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " ram_total_bytes, payload_hash_sha256, redacted_payload, "
                        + " probe_errors, collected_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, deviceId, 1, true,
                -100L,
                VALID_HASH,
                "{}", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_endpoint_hardware_inventory_snapshots_ram_total_range");
    }

    @Test
    void invalidMacFormatRejectedByCheck() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_network_interfaces "
                        + "(id, snapshot_id, tenant_id, mac_address, ip_addresses, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), snapshotId, tenantId,
                // Wave-12 PR-5 drive-by: the previous fixture
                // "INVALID-MAC-NOT-HEX" was 19 chars, which the
                // mac_address VARCHAR(17) column rejects on length
                // before the CHECK regex ever runs. Use a 17-char
                // value that fits the column but still trips the
                // lowercase-only CHECK ([0-9a-f]) — uppercase MAC
                // matches the colon shape and exact width but is
                // rejected by the format constraint.
                "AA:BB:CC:DD:EE:FF",
                "[]",
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_hardware_inventory_network_interfaces_mac_format");
    }

    // ──────────────────────────────────────────────────────────────────
    // Composite FK tenant-mismatch rejection
    // ──────────────────────────────────────────────────────────────────

    @Test
    void diskTenantMismatchRejectedByCompositeFk() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);

        // Disk row with a DIFFERENT tenant_id than the snapshot it
        // references. Composite FK enforces (snapshot_id, tenant_id)
        // pair — mismatch is a constraint violation.
        UUID wrongTenant = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_disks "
                        + "(id, snapshot_id, tenant_id, capacity_bytes, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, wrongTenant,
                100_000_000_000L,
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_endpoint_hardware_inventory_disks_snapshot");
    }

    @Test
    void networkInterfaceTenantMismatchRejectedByCompositeFk() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);

        UUID wrongTenant = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_network_interfaces "
                        + "(id, snapshot_id, tenant_id, mac_address, ip_addresses, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), snapshotId, wrongTenant,
                "aa:bb:cc:dd:ee:ff",
                "[]",
                Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_endpoint_hardware_inventory_network_interfaces_snapshot");
    }

    // ──────────────────────────────────────────────────────────────────
    // Partial UNIQUE on source_command_result_id (race catch path proof)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void duplicateSourceCommandResultIdRejectedByPartialUnique() {
        // Codex 019e7007 iter-4 P1 honesty: this proves the DB-level
        // partial UNIQUE constraint condition that the service's
        // saveAndFlush + DataIntegrityViolationException catch block
        // depends on. The service-level branch coverage of the catch
        // block itself lives in
        // EndpointHardwareInventoryServiceRaceCatchTest (Mockito-based
        // unit test that injects the exception).
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID resultId = insertCommandResult(tenantId, deviceId);

        // First snapshot OK.
        insertValidSnapshot(tenantId, deviceId, resultId);

        // Second snapshot for the SAME source_command_result_id
        // triggers the partial UNIQUE violation.
        UUID dupId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, source_command_result_id, "
                        + " schema_version, supported, payload_hash_sha256, "
                        + " redacted_payload, probe_errors, collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                dupId, tenantId, deviceId, resultId,
                1, true,
                VALID_HASH,
                "{}", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_endpoint_hardware_inventory_snapshots_source_cmd_result");
    }

    @Test
    void partialUniqueAllowsMultipleNullSourceCommandResultIds() {
        // NULL is allowed and does NOT participate in the unique
        // constraint — two snapshots with NULL source_command_result_id
        // must coexist (manual / test ingest paths).
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();

        insertValidSnapshot(tenantId, deviceId, null);
        insertValidSnapshot(tenantId, deviceId, null);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_snapshots "
                        + "WHERE tenant_id = ? AND device_id = ?",
                Long.class,
                tenantId, deviceId);
        assertThat(count).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // ON DELETE CASCADE / SET NULL behavior
    // ──────────────────────────────────────────────────────────────────

    @Test
    void deviceDeleteCascadesHardwareSnapshotDisksAndInterfaces() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, null);
        insertValidDisk(tenantId, snapshotId);
        insertValidInterface(tenantId, snapshotId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_snapshots",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_disks",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_network_interfaces",
                Long.class)).isEqualTo(1L);

        // Cascade through ON DELETE CASCADE (device → snapshot →
        // disks/NICs all gone).
        jdbc.update("DELETE FROM endpoint_devices WHERE id = ?", deviceId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_snapshots",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_disks",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM endpoint_hardware_inventory_network_interfaces",
                Long.class)).isZero();
    }

    @Test
    void commandResultDeleteSetsSourceCommandResultIdToNull() {
        UUID tenantId = persistTenantAndDevice();
        UUID deviceId = singleDeviceId();
        UUID resultId = insertCommandResult(tenantId, deviceId);
        UUID snapshotId = insertValidSnapshot(tenantId, deviceId, resultId);

        UUID currentRef = jdbc.queryForObject(
                "SELECT source_command_result_id FROM endpoint_hardware_inventory_snapshots "
                        + "WHERE id = ?",
                UUID.class, snapshotId);
        assertThat(currentRef).isEqualTo(resultId);

        // ON DELETE SET NULL — the snapshot survives but loses its
        // pointer (Codex iter-1 absorb).
        jdbc.update("DELETE FROM endpoint_command_results WHERE id = ?", resultId);

        UUID postDeleteRef = jdbc.queryForObject(
                "SELECT source_command_result_id FROM endpoint_hardware_inventory_snapshots "
                        + "WHERE id = ?",
                UUID.class, snapshotId);
        assertThat(postDeleteRef).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private UUID tenantId;
    private UUID deviceId;

    private UUID persistTenantAndDevice() {
        if (tenantId != null) return tenantId;
        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, last_seen_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deviceId, tenantId,
                "PG-TEST-HOST", "ONLINE", "WINDOWS",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);
        return tenantId;
    }

    private UUID singleDeviceId() {
        return deviceId;
    }

    private UUID insertCommandResult(UUID tenantId, UUID deviceId) {
        UUID commandId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_commands "
                        + "(id, tenant_id, device_id, command_type, status, "
                        + " idempotency_key, issued_by_subject, issued_at, "
                        + " attempt_count, max_attempts, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                commandId, tenantId, deviceId,
                "COLLECT_INVENTORY", "SUCCEEDED",
                "pg-test-" + UUID.randomUUID(),
                "test-admin@example.com",
                Timestamp.from(Instant.now()),
                0, 3,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);

        UUID resultId = UUID.randomUUID();
        // Codex 019e7007 iter-4 P1 fix: endpoint_command_results
        // schema has only `created_at` — no updated_at, no @Version
        // (verified against V2 baseline + EndpointCommandResult.java
        // entity mapping line 74).
        jdbc.update(
                "INSERT INTO endpoint_command_results "
                        + "(id, tenant_id, command_id, device_id, result_status, "
                        + " result_payload, reported_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                resultId, tenantId, commandId, deviceId,
                "SUCCEEDED",
                "{}",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return resultId;
    }

    private UUID insertValidSnapshot(UUID tenantId, UUID deviceId, UUID resultIdOrNull) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, source_command_result_id, "
                        + " schema_version, supported, payload_hash_sha256, "
                        + " redacted_payload, probe_errors, collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                snapshotId, tenantId, deviceId, resultIdOrNull,
                1, true,
                VALID_HASH,
                "{}", "[]",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                0L);
        return snapshotId;
    }

    private void insertValidDisk(UUID tenantId, UUID snapshotId) {
        jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_disks "
                        + "(id, snapshot_id, tenant_id, capacity_bytes, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), snapshotId, tenantId,
                100_000_000_000L,
                Timestamp.from(Instant.now()));
    }

    private void insertValidInterface(UUID tenantId, UUID snapshotId) {
        jdbc.update(
                "INSERT INTO endpoint_hardware_inventory_network_interfaces "
                        + "(id, snapshot_id, tenant_id, mac_address, ip_addresses, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), snapshotId, tenantId,
                "aa:bb:cc:dd:ee:ff",
                "[]",
                Timestamp.from(Instant.now()));
    }
}
