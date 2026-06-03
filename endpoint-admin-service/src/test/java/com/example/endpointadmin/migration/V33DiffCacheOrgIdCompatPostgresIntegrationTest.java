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
 * Faz 21.1 PR2c regression guard — V33 cache table org_id compat
 * migration mirrors V29 (source tables) + V30 (CHECK constraint) onto
 * the two diff-cache tables (V27/V28 lineage):
 *
 * <ul>
 *   <li>ADD COLUMN org_id UUID nullable</li>
 *   <li>BACKFILL existing rows (org_id = tenant_id)</li>
 *   <li>BEFORE INSERT/UPDATE trigger fills org_id from tenant_id when
 *       caller omits</li>
 *   <li>CHECK org_id IS NULL OR org_id = tenant_id (V30 pattern)</li>
 * </ul>
 *
 * <p>Eight assertions covering both cache tables (Codex 019e8e29 iter-1
 * REVISE #4 absorb: backfill is NOT asserted here because
 * {@code @DataJpaTest} runs all migrations including V33 at context
 * boot, so this PG IT only exercises the post-V33 trigger + CHECK +
 * canonical write paths. Backfill correctness for pre-V33 rows is a
 * deploy-time migration property; covered by V29 charter pre-flight
 * evidence at staging-sw rollout):
 * <ol>
 *   <li>Software diff cache: ADD COLUMN org_id exists and is nullable.</li>
 *   <li>Software diff cache: trigger fills org_id from tenant_id when
 *       INSERT omits.</li>
 *   <li>Software diff cache: CHECK rejects mismatched org_id.</li>
 *   <li>Software diff cache: canonical write (org_id = tenant_id
 *       explicit) accepted.</li>
 *   <li>Outdated diff cache: ADD COLUMN org_id exists and is nullable.</li>
 *   <li>Outdated diff cache: trigger fills org_id.</li>
 *   <li>Outdated diff cache: CHECK rejects mismatched org_id.</li>
 *   <li>Outdated diff cache: canonical write accepted.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V33DiffCacheOrgIdCompatPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    // ───────────────────────── Software diff cache ─────────────────────────

    @Test
    void software_addColumnOrgIdIsNullable() {
        Boolean isNullable = jdbc.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = 'org_id'",
                Boolean.class, SCHEMA, "endpoint_software_diff_cache");
        assertThat(isNullable)
                .as("org_id column is nullable in pre-cleanup state (per V33 charter)")
                .isTrue();
    }

    @Test
    void software_triggerFillsOrgIdFromTenantIdOnInsert() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        UUID rowId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Legacy writer pattern: omit org_id; V33 trigger should fill it.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                rowId, tenant, deviceId, now, now, UUID.randomUUID(), now);

        UUID storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA
                        + ".endpoint_software_diff_cache WHERE id = ?",
                UUID.class, rowId);
        assertThat(storedOrgId)
                .as("V33 trigger fills org_id from tenant_id when caller omits")
                .isEqualTo(tenant);
    }

    @Test
    void software_checkRejectsMismatchedOrgId() {
        UUID tenant = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, otherOrg, deviceId,
                now, now, UUID.randomUUID(), now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("swdc_org_id_eq_tenant_id_ck");
    }

    @Test
    void software_canonicalWriteOrgIdEqualsTenantIdAccepted() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        UUID rowId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Canonical writer: org_id = tenant_id explicitly.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                rowId, tenant, tenant, deviceId, now, now, UUID.randomUUID(), now);

        UUID storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA
                        + ".endpoint_software_diff_cache WHERE id = ?",
                UUID.class, rowId);
        assertThat(storedOrgId).isEqualTo(tenant);
    }

    // ───────────────────────── Outdated diff cache ─────────────────────────

    @Test
    void outdated_addColumnOrgIdIsNullable() {
        Boolean isNullable = jdbc.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = 'org_id'",
                Boolean.class, SCHEMA, "endpoint_outdated_software_diff_cache");
        assertThat(isNullable).isTrue();
    }

    @Test
    void outdated_triggerFillsOrgIdFromTenantIdOnInsert() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        UUID rowId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, 'NO_HISTORY', 0, 0, 0, 0, ?, ?, ?, ?)",
                rowId, tenant, deviceId, now, now, UUID.randomUUID(), now);

        UUID storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA
                        + ".endpoint_outdated_software_diff_cache WHERE id = ?",
                UUID.class, rowId);
        assertThat(storedOrgId).isEqualTo(tenant);
    }

    @Test
    void outdated_checkRejectsMismatchedOrgId() {
        UUID tenant = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, otherOrg, deviceId,
                now, now, UUID.randomUUID(), now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("osdc_org_id_eq_tenant_id_ck");
    }

    @Test
    void outdated_canonicalWriteOrgIdEqualsTenantIdAccepted() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = seedDevice(tenant);
        UUID rowId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, org_id, device_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, "
                        + " source_captured_at, source_created_at, source_row_id, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, ?, 'NO_HISTORY', 0, 0, 0, 0, ?, ?, ?, ?)",
                rowId, tenant, tenant, deviceId, now, now, UUID.randomUUID(), now);

        UUID storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA
                        + ".endpoint_outdated_software_diff_cache WHERE id = ?",
                UUID.class, rowId);
        assertThat(storedOrgId).isEqualTo(tenant);
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private UUID seedDevice(UUID tenant) {
        UUID deviceId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + " os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                deviceId, tenant, tenant,
                "host-" + deviceId,
                "fp-" + deviceId,
                now, now);
        return deviceId;
    }
}
