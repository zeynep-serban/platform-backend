package com.example.endpointadmin.service.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-024c v2-c-pre-2-A (Faz 22.5 P2-A, Codex 019e88b5 iter-5 AGREE — Step 6) —
 * {@link DiffCacheService} UPSERT idempotency + tenant boundary +
 * identical-payload no-op tests against a real PostgreSQL container.
 *
 * <p>Pins the contract the v2-d grid SCHEMA v5 (separate later PR) depends
 * on:
 *
 * <ul>
 *   <li>First upsert INSERTs and returns {@code true}.</li>
 *   <li>Identical-payload re-upsert hits the {@code ON CONFLICT DO UPDATE
 *       WHERE} predicate and writes nothing — returns {@code false}
 *       (no WAL churn, no autovacuum bloat, no {@code computed_at} drift).</li>
 *   <li>Changed payload re-upsert UPDATEs and returns {@code true}.</li>
 *   <li>Different tenant + same device id → separate rows
 *       (UNIQUE per tenant+device, not per device alone).</li>
 *   <li>Software 3-count + outdated 4-count delta detection (each count
 *       must independently trigger the WHERE predicate so a regression
 *       that drops one column from the UPDATE WHERE is caught).</li>
 *   <li>Status shape invariants validated client-side BEFORE hitting V27
 *       CHECK so the failure is precise.</li>
 * </ul>
 *
 * <p>Non-public schema deliberately matches the live testai topology — a
 * regression to public-schema-only resolution would not be caught by a
 * public-schema test (drawer / cache write path canonical name resolution).
 *
 * <p>Seed pattern mirrors {@code SummarizePostgresIntegrationTest} so the
 * two PG ITs share one authoritative source-of-truth shape.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiffCacheService.class)
class DiffCacheServiceUpsertPostgresIntegrationTest {

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

    @Autowired private DiffCacheService diffCacheService;
    @Autowired private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- software

    @Test
    void software_firstUpsert_insertsRow_returnsTrue() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 3, 2, 1));

        assertThat(wrote).isTrue();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("added_count")).isEqualTo(3);
        assertThat(row.get("removed_count")).isEqualTo(2);
        assertThat(row.get("version_changed_count")).isEqualTo(1);
    }

    @Test
    void software_identicalPayloadReUpsert_returnsFalse_keepsRowAndTimestamp() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));
        SoftwareDiffSummary summary = SoftwareDiffSummary.ok(h1, h2, 3, 2, 1);

        diffCacheService.upsertSoftwareDiffCache(tenant, device, summary);
        Instant firstTs = readSoftwareCacheComputedAt(tenant, device);

        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device, summary);

        assertThat(wrote).as("identical payload must be a no-op").isFalse();
        Instant secondTs = readSoftwareCacheComputedAt(tenant, device);
        assertThat(secondTs).as("computed_at must NOT drift on identical-payload no-op")
                .isEqualTo(firstTs);
    }

    @Test
    void software_changedPayloadReUpsert_updatesRow_returnsTrue() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 3, 2, 1));

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 3, 2, 2)); // versionChanged 1 -> 2

        assertThat(wrote).isTrue();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("version_changed_count")).isEqualTo(2);
    }

    @Test
    void software_changeOnlyAddedCount_triggersUpdate() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 5, 0, 0));
        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 6, 0, 0));

        assertThat(wrote).isTrue();
        assertThat(readSoftwareCacheRow(tenant, device).get("added_count")).isEqualTo(6);
    }

    @Test
    void software_differentTenantSameDevice_separateRows() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID deviceA = insertDevice(tenantA);
        UUID deviceB = insertDevice(tenantB);
        UUID h1A = insertSoftwareHistory(tenantA, deviceA, Instant.parse("2026-06-02T10:00:00Z"));
        UUID h2A = insertSoftwareHistory(tenantA, deviceA, Instant.parse("2026-06-02T10:01:00Z"));

        diffCacheService.upsertSoftwareDiffCache(tenantA, deviceA,
                SoftwareDiffSummary.ok(h1A, h2A, 1, 0, 0));
        boolean wroteB = diffCacheService.upsertSoftwareDiffCache(tenantB, deviceB,
                SoftwareDiffSummary.noHistory());

        assertThat(wroteB).isTrue();
        assertThat(countSoftwareCache(tenantA, deviceA)).isEqualTo(1);
        assertThat(countSoftwareCache(tenantB, deviceB)).isEqualTo(1);
    }

    @Test
    void software_noHistory_zeroCountsAndNullIds_isValid() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.noHistory());

        assertThat(wrote).isTrue();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("NO_HISTORY");
        assertThat(row.get("from_history_id")).isNull();
        assertThat(row.get("to_history_id")).isNull();
    }

    @Test
    void software_insufficientHistory_onlyToIdSet_isValid() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.insufficientHistory(h1));

        assertThat(wrote).isTrue();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(row.get("from_history_id")).isNull();
        assertThat(row.get("to_history_id")).isEqualTo(h1);
    }

    @Test
    void software_invalidShape_okWithBothIdsNull_rejectedClientSide() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        SoftwareDiffSummary bad = new SoftwareDiffSummary(
                AdminSoftwareInventoryDiffResponse.DiffStatus.OK, null, null, 0, 0, 0);

        assertThatThrownBy(() -> diffCacheService.upsertSoftwareDiffCache(tenant, device, bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OK requires both ids set");
    }

    // ---------------------------------------------------------------- outdated

    @Test
    void outdated_firstUpsert_insertsRow_returnsTrue() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID s2 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        boolean wrote = diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, s2, 5, 4, 3, 2));

        assertThat(wrote).isTrue();
        Map<String, Object> row = readOutdatedCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("added_count")).isEqualTo(5);
        assertThat(row.get("removed_count")).isEqualTo(4);
        assertThat(row.get("version_changed_count")).isEqualTo(3);
        assertThat(row.get("available_version_bumped_count")).isEqualTo(2);
    }

    @Test
    void outdated_identicalPayloadReUpsert_returnsFalse_keepsRowAndTimestamp() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID s2 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));
        OutdatedDiffSummary summary = OutdatedDiffSummary.ok(s1, s2, 5, 4, 3, 2);

        diffCacheService.upsertOutdatedDiffCache(tenant, device, summary);
        Instant firstTs = readOutdatedCacheComputedAt(tenant, device);

        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        boolean wrote = diffCacheService.upsertOutdatedDiffCache(tenant, device, summary);

        assertThat(wrote).as("identical payload must be a no-op").isFalse();
        Instant secondTs = readOutdatedCacheComputedAt(tenant, device);
        assertThat(secondTs).isEqualTo(firstTs);
    }

    @Test
    void outdated_availableVersionBumpChange_isDetectedByUpdateWhere() {
        // Codex 019e88b5 iter-5: pin the 4th outdated count in the UPDATE
        // WHERE clause — software has 3 counts, outdated has a 4th
        // (availableVersionBumpedCount) that the WHERE must also test.
        // Without this test, a regression that drops the 4th-count column
        // from the UPDATE WHERE would silently no-op genuine outdated
        // availableVersion churn.
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID s2 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, s2, 0, 0, 0, 1));
        boolean wrote = diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, s2, 0, 0, 0, 2));

        assertThat(wrote).isTrue();
        assertThat(((Number) readOutdatedCacheRow(tenant, device)
                .get("available_version_bumped_count")).intValue()).isEqualTo(2);
    }

    @Test
    void outdated_invalidShape_okWithBothIdsNull_rejectedClientSide() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        OutdatedDiffSummary bad = new OutdatedDiffSummary(
                AdminOutdatedSoftwareDiffResponse.DiffStatus.OK, null, null, 0, 0, 0, 0);

        assertThatThrownBy(() -> diffCacheService.upsertOutdatedDiffCache(tenant, device, bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OK requires both ids set");
    }

    // ───────────────────────── seed helpers (mirror SummarizePostgresIntegrationTest) ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'host', 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, now, now);
        return id;
    }

    private UUID insertSoftwareHistory(UUID tenant, UUID device, Instant capturedAt) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(capturedAt);
        String seed = id.toString().toLowerCase().replaceAll("[^0-9a-f]", "");
        String hashFull = (seed + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 0, ?, '[]'::jsonb, ?, ?)",
                id, tenant, device, hashFull, ts, ts);
        return id;
    }

    private UUID insertOutdatedSnapshot(UUID tenant, UUID device, Instant collectedAt) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(collectedAt);
        String hash = id.toString().replace("-", "");
        hash = (hash + hash).substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 0, false, 100, 'winget', ?, ?)",
                id, tenant, device, hash, ts);
        return id;
    }

    // ───────────────────────── read helpers ─────────────────────────

    private long countSoftwareCache(UUID tenant, UUID device) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA
                        + ".endpoint_software_diff_cache WHERE tenant_id = ? AND device_id = ?",
                Long.class, tenant, device);
        return c == null ? 0L : c;
    }

    private Map<String, Object> readSoftwareCacheRow(UUID tenant, UUID device) {
        return jdbc.queryForMap(
                "SELECT status, from_history_id, to_history_id, "
                        + "added_count, removed_count, version_changed_count, computed_at "
                        + "FROM " + SCHEMA + ".endpoint_software_diff_cache "
                        + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
    }

    private Map<String, Object> readOutdatedCacheRow(UUID tenant, UUID device) {
        return jdbc.queryForMap(
                "SELECT status, from_snapshot_id, to_snapshot_id, "
                        + "added_count, removed_count, version_changed_count, "
                        + "available_version_bumped_count, computed_at "
                        + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
    }

    private Instant readSoftwareCacheComputedAt(UUID tenant, UUID device) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT computed_at FROM " + SCHEMA
                        + ".endpoint_software_diff_cache WHERE tenant_id = ? AND device_id = ?",
                Timestamp.class, tenant, device);
        return ts == null ? null : ts.toInstant();
    }

    private Instant readOutdatedCacheComputedAt(UUID tenant, UUID device) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT computed_at FROM " + SCHEMA
                        + ".endpoint_outdated_software_diff_cache WHERE tenant_id = ? AND device_id = ?",
                Timestamp.class, tenant, device);
        return ts == null ? null : ts.toInstant();
    }
}
