package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
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
 * BE-024 — PostgreSQL-only migration + tenant-integrity integration tests
 * for {@code V18__endpoint_software_inventory_state_history.sql} (Faz 22.5).
 * Mirrors {@code EndpointDeviceHealthPostgresIntegrationTest} (V17).
 *
 * <p>The H2 {@code @DataJpaTest} slice cannot exercise the parts that
 * depend on real Postgres semantics:
 * <ul>
 *   <li>Composite-FK enforcement on {@code (device_id, tenant_id)} — H2
 *       silently accepts cross-tenant inserts;</li>
 *   <li>Partial UNIQUE on {@code source_command_result_id};</li>
 *   <li>DB CHECK regex on {@code apps_digest_hash}, the
 *       {@code schema_version} / {@code app_count} range CHECKs, and the
 *       {@code jsonb_typeof(apps_digest) = 'array'} CHECK;</li>
 *   <li>{@code ON DELETE CASCADE} (device) / {@code ON DELETE SET NULL}
 *       (command-result).</li>
 * </ul>
 *
 * <p>PG 16 Testcontainer + Flyway enabled + {@code ddl-auto=validate} +
 * {@code public} schema (same setup as the V13 / V17 PG tests). Runs in CI
 * only — the local {@code -Dtest='!*PostgresIntegrationTest'} filter skips
 * it (Docker unavailable locally).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointSoftwareInventoryStateHistoryPostgresIntegrationTest {

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

    private static final String TABLE = "endpoint_software_inventory_state_history";
    private static final String VALID_HASH = "a".repeat(64);

    @Autowired
    private EndpointSoftwareInventoryStateHistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    // ──────────────────────────────────────────────────────────────────
    // Flyway / schema validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void flywayLiftsSchemaAndHibernateValidatesAgainstIt() {
        // ddl-auto=validate context start is itself the assertion: every
        // column in EndpointSoftwareInventoryStateHistory must line up with
        // V18 or Spring refuses to bring the test context up.
        assertThat(historyRepository).isNotNull();
        assertThat(historyRepository.count()).isZero();
    }

    @Test
    void v18RegistersExpectedConstraintsAndIndexes() {
        List<String> checks = jdbc.queryForList(
                "SELECT conname FROM pg_catalog.pg_constraint "
                        + "WHERE conrelid = 'public." + TABLE + "'::regclass "
                        + "AND contype = 'c'",
                String.class);
        assertThat(checks).contains(
                "ck_endpoint_software_inventory_state_history_schema_version",
                "ck_endpoint_software_inventory_state_history_app_count_range",
                "ck_endpoint_software_inventory_state_history_hash_format",
                "ck_endpoint_software_inventory_state_history_digest_shape");

        List<String> partialUnique = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = '" + TABLE + "' "
                        + "AND indexname = "
                        + "'uq_endpoint_software_inventory_state_history_source_cmd_result'",
                String.class);
        assertThat(partialUnique).hasSize(1);

        List<String> latestIndex = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE tablename = '" + TABLE + "' "
                        + "AND indexname = "
                        + "'idx_endpoint_software_inventory_state_history_tenant_dev_time'",
                String.class);
        assertThat(latestIndex).hasSize(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // DB CHECK constraint violations
    // ──────────────────────────────────────────────────────────────────

    @Test
    void invalidHashRegexRejectedByCheck() {
        persistTenantAndDevice();
        assertThatThrownBy(() -> insertRaw(
                tenantId, deviceId, null, 1, 0, "NOT_A_VALID_HASH", "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_hash_format");
    }

    @Test
    void negativeAppCountRejectedByCheck() {
        persistTenantAndDevice();
        assertThatThrownBy(() -> insertRaw(
                tenantId, deviceId, null, 1, -1, VALID_HASH, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_app_count_range");
    }

    @Test
    void zeroSchemaVersionRejectedByCheck() {
        persistTenantAndDevice();
        assertThatThrownBy(() -> insertRaw(
                tenantId, deviceId, null, 0, 0, VALID_HASH, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_schema_version");
    }

    @Test
    void nonArrayDigestRejectedByCheck() {
        persistTenantAndDevice();
        // A JSON object (not an array) trips the jsonb_typeof CHECK.
        assertThatThrownBy(() -> insertRaw(
                tenantId, deviceId, null, 1, 0, VALID_HASH, "{\"a\":1}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_digest_shape");
    }

    // ──────────────────────────────────────────────────────────────────
    // Composite FK tenant-mismatch rejection
    // ──────────────────────────────────────────────────────────────────

    @Test
    void historyTenantMismatchRejectedByCompositeFk() {
        persistTenantAndDevice();
        UUID wrongTenant = UUID.randomUUID();
        // device_id is real, but tenant_id mismatches → composite FK fails.
        assertThatThrownBy(() -> insertRaw(
                deviceIdOwner(wrongTenant), deviceId, null, 1, 0, VALID_HASH, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "sw_inv_device_org_fk");
    }

    // ──────────────────────────────────────────────────────────────────
    // Partial UNIQUE on source_command_result_id
    // ──────────────────────────────────────────────────────────────────

    @Test
    void duplicateSourceCommandResultIdRejectedByPartialUnique() {
        persistTenantAndDevice();
        UUID resultId = insertCommandResult(tenantId, deviceId);
        insertRaw(tenantId, deviceId, resultId, 1, 0, VALID_HASH, "[]");

        assertThatThrownBy(() -> insertRaw(
                tenantId, deviceId, resultId, 1, 0, VALID_HASH, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "uq_endpoint_software_inventory_state_history_source_cmd_result");
    }

    @Test
    void partialUniqueAllowsMultipleNullSourceCommandResultIds() {
        persistTenantAndDevice();
        insertRaw(tenantId, deviceId, null, 1, 0, VALID_HASH, "[]");
        insertRaw(tenantId, deviceId, null, 1, 0, VALID_HASH, "[]");

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE
                        + " WHERE tenant_id = ? AND device_id = ?",
                Long.class, tenantId, deviceId);
        assertThat(count).isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // ON DELETE CASCADE / SET NULL
    // ──────────────────────────────────────────────────────────────────

    @Test
    void deviceDeleteCascadesHistory() {
        persistTenantAndDevice();
        insertRaw(tenantId, deviceId, null, 1, 0, VALID_HASH, "[]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Long.class)).isEqualTo(1L);

        jdbc.update("DELETE FROM endpoint_devices WHERE id = ?", deviceId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Long.class)).isZero();
    }

    @Test
    void commandResultDeleteSetsSourceCommandResultIdToNull() {
        persistTenantAndDevice();
        UUID resultId = insertCommandResult(tenantId, deviceId);
        UUID historyId = insertRaw(tenantId, deviceId, resultId, 1, 0, VALID_HASH, "[]");

        jdbc.update("DELETE FROM endpoint_command_results WHERE id = ?", resultId);

        UUID postDeleteRef = jdbc.queryForObject(
                "SELECT source_command_result_id FROM " + TABLE + " WHERE id = ?",
                UUID.class, historyId);
        assertThat(postDeleteRef).isNull();
    }

    @Test
    void validDigestArrayPersistsAndReadsBack() {
        persistTenantAndDevice();
        String digest = "[{\"appKey\":\"k1\",\"displayName\":\"7-Zip\","
                + "\"publisher\":\"Igor Pavlov\",\"version\":\"24.07\","
                + "\"msiProductCodeHash\":null}]";
        UUID historyId = insertRaw(tenantId, deviceId, null, 1, 1, VALID_HASH, digest);

        Integer appCount = jdbc.queryForObject(
                "SELECT app_count FROM " + TABLE + " WHERE id = ?",
                Integer.class, historyId);
        assertThat(appCount).isEqualTo(1);
        String typeof = jdbc.queryForObject(
                "SELECT jsonb_typeof(apps_digest) FROM " + TABLE + " WHERE id = ?",
                String.class, historyId);
        assertThat(typeof).isEqualTo("array");
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private UUID tenantId;
    private UUID deviceId;

    private void persistTenantAndDevice() {
        if (tenantId != null) {
            return;
        }
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
    }

    /** Returns a tenant id that does NOT own {@link #deviceId} (used to
     *  drive the composite-FK mismatch test). */
    private UUID deviceIdOwner(UUID wrongTenant) {
        return wrongTenant;
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
        jdbc.update(
                "INSERT INTO endpoint_command_results "
                        + "(id, tenant_id, command_id, device_id, result_status, "
                        + " result_payload, reported_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                resultId, tenantId, commandId, deviceId,
                "SUCCEEDED", "{}",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return resultId;
    }

    private UUID insertRaw(UUID tenantId, UUID deviceId, UUID resultIdOrNull,
                           int schemaVersion, int appCount, String hash,
                           String digestJson) {
        UUID historyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO " + TABLE
                        + " (id, tenant_id, device_id, source_command_result_id, "
                        + "  schema_version, app_count, apps_digest_hash, apps_digest, "
                        + "  captured_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                historyId, tenantId, deviceId, resultIdOrNull,
                schemaVersion, appCount, hash, digestJson,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return historyId;
    }
}
