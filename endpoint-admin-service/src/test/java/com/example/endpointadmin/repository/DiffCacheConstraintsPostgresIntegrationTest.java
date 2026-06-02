package com.example.endpointadmin.repository;

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
 * BE-024c (Faz 22.5 P2-A v2-c-pre, Codex 019e88b5 iter-5 AGREE) — V27
 * cache table integrity tests against a real PostgreSQL container.
 *
 * <p>Pins the CHECK + FK constraints that the cache write path depends
 * on at the DB layer:
 * <ul>
 *   <li>status shape invariant (status pair must match source-id presence).</li>
 *   <li>non-OK counts-zero invariant.</li>
 *   <li>composite (id, tenant_id) FK to state_history + outdated_snapshots
 *       ON DELETE CASCADE.</li>
 *   <li>(tenant_id, device_id) UNIQUE.</li>
 * </ul>
 *
 * <p>Non-public schema (mirrors the live testai topology) so a regression
 * to public-schema-only constraint names cannot ship.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DiffCacheConstraintsPostgresIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v27MigrationApplied_createsBothCacheTables() {
        Integer software = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + ".endpoint_software_diff_cache", Integer.class);
        Integer outdated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache",
                Integer.class);
        assertThat(software).isZero();
        assertThat(outdated).isZero();
    }

    @Test
    void softwareCache_statusShapeInvariant_rejectsOkWithoutBothHistoryIds() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        // OK with NULL source pair → swdc_status_shape_ck violation.
        assertThatThrownBy(() -> insertSoftwareCache(
                UUID.randomUUID(), tenant, device,
                null, null, "OK", 1, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("swdc_status_shape_ck");
    }

    @Test
    void softwareCache_statusShapeInvariant_rejectsNoHistoryWithSourceIds() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID hist = insertSoftwareStateHistory(tenant, device);

        // NO_HISTORY with non-null source → swdc_status_shape_ck violation.
        assertThatThrownBy(() -> insertSoftwareCache(
                UUID.randomUUID(), tenant, device,
                hist, hist, "NO_HISTORY", 0, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("swdc_status_shape_ck");
    }

    @Test
    void softwareCache_nonOkCountsZeroInvariant_rejectsNoChangeWithCounts() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareStateHistory(tenant, device);
        UUID h2 = insertSoftwareStateHistory(tenant, device);

        // NO_CHANGE with non-zero added_count → swdc_non_ok_counts_zero_ck.
        assertThatThrownBy(() -> insertSoftwareCache(
                UUID.randomUUID(), tenant, device,
                h1, h2, "NO_CHANGE", 1, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("swdc_non_ok_counts_zero_ck");
    }

    @Test
    void softwareCache_acceptsOkWithCountsAndBothHistoryIds() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareStateHistory(tenant, device);
        UUID h2 = insertSoftwareStateHistory(tenant, device);

        UUID cacheId = UUID.randomUUID();
        insertSoftwareCache(cacheId, tenant, device, h1, h2, "OK", 3, 1, 2);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + ".endpoint_software_diff_cache "
                        + "WHERE id = ?",
                Integer.class, cacheId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void softwareCache_compositeFkOnDeleteCascade_removesCacheWhenHistoryDeleted() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareStateHistory(tenant, device);
        UUID h2 = insertSoftwareStateHistory(tenant, device);

        UUID cacheId = UUID.randomUUID();
        insertSoftwareCache(cacheId, tenant, device, h1, h2, "OK", 1, 0, 0);

        // Delete one of the source history rows — cascade should drop the cache row.
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_software_inventory_state_history "
                + "WHERE id = ? AND tenant_id = ?", h2, tenant);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + ".endpoint_software_diff_cache "
                        + "WHERE id = ?",
                Integer.class, cacheId);
        assertThat(count).isZero();
    }

    @Test
    void softwareCache_tenantDeviceUnique_rejectsDuplicate() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        insertSoftwareCache(UUID.randomUUID(), tenant, device,
                null, null, "NO_HISTORY", 0, 0, 0);

        // Second row with the same (tenant, device) → swdc_tenant_device_uq.
        assertThatThrownBy(() -> insertSoftwareCache(
                UUID.randomUUID(), tenant, device,
                null, null, "NO_HISTORY", 0, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("swdc_tenant_device_uq");
    }

    @Test
    void outdatedCache_statusShapeInvariant_rejectsOkWithoutBothSnapshotIds() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        assertThatThrownBy(() -> insertOutdatedCache(
                UUID.randomUUID(), tenant, device,
                null, null, "OK", 1, 0, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("osdc_status_shape_ck");
    }

    @Test
    void outdatedCache_nonOkCountsZeroInvariant_rejectsNoChangeWithCounts() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device);
        UUID s2 = insertOutdatedSnapshot(tenant, device);

        // NO_CHANGE with non-zero availableVersionBumpedCount → osdc_non_ok_counts_zero_ck.
        assertThatThrownBy(() -> insertOutdatedCache(
                UUID.randomUUID(), tenant, device,
                s1, s2, "NO_CHANGE", 0, 0, 0, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("osdc_non_ok_counts_zero_ck");
    }

    // ───────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'host', 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, now, now);
        return id;
    }

    private UUID insertSoftwareStateHistory(UUID tenant, UUID device) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        // Vary the payload_hash per insert (UNIQUE on tenant+device+payload_hash).
        String base = id.toString().replace("-", "");
        String hash = (base + base).substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, 1, "
                        + "        0, ?, '[]'::jsonb, "
                        + "        ?, ?)",
                id, tenant, device, hash, now, now);
        return id;
    }

    private UUID insertOutdatedSnapshot(UUID tenant, UUID device) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        String base = id.toString().replace("-", "");
        String hash = (base + base).substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 0, false, 100, 'winget', ?, ?)",
                id, tenant, device, hash, now);
        return id;
    }

    private void insertSoftwareCache(UUID id, UUID tenant, UUID device,
                                      UUID fromHistoryId, UUID toHistoryId,
                                      String status,
                                      int addedCount, int removedCount, int versionChangedCount) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, device, fromHistoryId, toHistoryId,
                status, addedCount, removedCount, versionChangedCount, now);
    }

    private void insertOutdatedCache(UUID id, UUID tenant, UUID device,
                                      UUID fromSnapshotId, UUID toSnapshotId,
                                      String status,
                                      int addedCount, int removedCount,
                                      int versionChangedCount,
                                      int availableVersionBumpedCount) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, from_snapshot_id, to_snapshot_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, device, fromSnapshotId, toSnapshotId,
                status, addedCount, removedCount,
                versionChangedCount, availableVersionBumpedCount, now);
    }
}
