package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * BE-024c v2-c-pre-2-C-C Category B++ — V28 backfill migration harness
 * PG IT (Codex 019e8a09 iter-1 deferred coverage). The other diff PG IT
 * classes run Flyway to V28 head before any test, so the JOIN-backfill +
 * RAISE EXCEPTION branches of V28 are never exercised by them. This test
 * uses a fresh testcontainer + explicit Flyway with {@code target=V27}
 * baseline, seeds pre-V28 cache + source rows, then runs V28 manually and
 * asserts the backfill correctness.
 *
 * <p>Categories:
 * <ul>
 *   <li><b>software JOIN-backfill</b> — cache row with status=OK and a
 *       valid to_history_id gets its source_* tuple populated from the
 *       source state_history row.</li>
 *   <li><b>outdated JOIN-backfill</b> — mirror for outdated path against
 *       outdated_snapshots.</li>
 *   <li><b>NO_HISTORY sentinel</b> — cache row with status=NO_HISTORY and
 *       to_history_id NULL gets epoch + zero-uuid sentinel.</li>
 *   <li><b>fail-loud RAISE</b> — cache row pointing to a deleted (orphan)
 *       source row triggers the V28 RAISE EXCEPTION (negative path).</li>
 * </ul>
 *
 * <p>This is a focused migration harness; it does NOT exercise the
 * runtime DiffCacheService writer logic (covered by the other PG IT
 * classes).
 */
@Testcontainers
class V28BackfillMigrationPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    private DriverManagerDataSource freshDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private void resetSchemaToV27(DriverManagerDataSource ds) {
        // Drop + recreate schema, then migrate to V27 only.
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("27")
                .load();
        flyway.migrate();
    }

    private void runV28(DriverManagerDataSource ds) {
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("28")
                .load();
        flyway.migrate();
    }

    @Test
    void software_okStatus_backfillsTupleFromSourceHistory() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        UUID h1 = insertSoftwareHistory(jdbc, tenant, device, t1, t1);
        UUID h2 = insertSoftwareHistory(jdbc, tenant, device, t2, t2);

        // Pre-V28 cache row WITHOUT source_* columns (V27 schema).
        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OK', 3, 2, 1, ?)",
                cacheId, tenant, device, h1, h2, now);

        runV28(ds);

        // V28 JOIN-backfill should have populated source_* from h2 (the
        // to_history_id).
        Map<String, Object> row = jdbc.queryForList(
                "SELECT source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_software_diff_cache WHERE id = ?",
                cacheId).get(0);
        assertThat(row.get("source_captured_at")).isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_created_at")).isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_row_id")).isEqualTo(h2);
    }

    @Test
    void software_noHistoryStatus_backfillsEpochZeroSentinel() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);

        // Cache row with status=NO_HISTORY, to_history_id NULL — V28
        // should fill with epoch + zero-uuid sentinel.
        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, computed_at) "
                        + "VALUES (?, ?, ?, NULL, NULL, 'NO_HISTORY', 0, 0, 0, ?)",
                cacheId, tenant, device, now);

        runV28(ds);

        Map<String, Object> row = jdbc.queryForList(
                "SELECT source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_software_diff_cache WHERE id = ?",
                cacheId).get(0);
        assertThat(row.get("source_captured_at"))
                .isEqualTo(Timestamp.from(Instant.parse("1970-01-01T00:00:00Z")));
        assertThat(row.get("source_created_at"))
                .isEqualTo(Timestamp.from(Instant.parse("1970-01-01T00:00:00Z")));
        assertThat(row.get("source_row_id"))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void outdated_okStatus_backfillsTupleFromSnapshot() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        UUID s1 = insertOutdatedSnapshot(jdbc, tenant, device, t1, t1);
        UUID s2 = insertOutdatedSnapshot(jdbc, tenant, device, t2, t2);

        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, from_snapshot_id, to_snapshot_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OK', 5, 4, 3, 2, ?)",
                cacheId, tenant, device, s1, s2, now);

        runV28(ds);

        Map<String, Object> row = jdbc.queryForList(
                "SELECT source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache WHERE id = ?",
                cacheId).get(0);
        assertThat(row.get("source_captured_at")).isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_created_at")).isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_row_id")).isEqualTo(s2);
    }

    @Test
    void outdated_noHistoryStatus_backfillsEpochZeroSentinel() {
        // Codex 019e8a25 iter-1 medium absorb: outdated sentinel SQL is
        // a separate block from software's; software_noHistoryStatus_*
        // does NOT prove this branch works. Mirror test for outdated.
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);

        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, from_snapshot_id, to_snapshot_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, computed_at) "
                        + "VALUES (?, ?, ?, NULL, NULL, 'NO_HISTORY', 0, 0, 0, 0, ?)",
                cacheId, tenant, device, now);

        runV28(ds);

        Map<String, Object> row = jdbc.queryForList(
                "SELECT source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache WHERE id = ?",
                cacheId).get(0);
        assertThat(row.get("source_captured_at"))
                .isEqualTo(Timestamp.from(Instant.parse("1970-01-01T00:00:00Z")));
        assertThat(row.get("source_created_at"))
                .isEqualTo(Timestamp.from(Instant.parse("1970-01-01T00:00:00Z")));
        assertThat(row.get("source_row_id"))
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void outdated_okWithOrphanToSnapshotId_failLoudRaiseException() {
        // Codex 019e8a25 iter-1 nice-to-have: outdated fail-loud RAISE
        // mirror. Same DROP CONSTRAINT pattern to bypass FK so orphan
        // can be inserted.
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID ghostSnapshotId = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffd");

        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                + "DROP CONSTRAINT IF EXISTS osdc_to_snapshot_fk");

        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, from_snapshot_id, to_snapshot_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, computed_at) "
                        + "VALUES (?, ?, ?, NULL, ?, 'INSUFFICIENT_HISTORY', 0, 0, 0, 0, ?)",
                cacheId, tenant, device, ghostSnapshotId, now);

        try {
            runV28(ds);
            assertThat(false).as("expected V28 to fail-loud on orphan to_snapshot_id").isTrue();
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage())
                    .as("V28 RAISE EXCEPTION for outdated orphan path")
                    .contains("V28 migration failed");
        }
    }

    @Test
    void software_okWithOrphanToHistoryId_failLoudRaiseException() {
        // Cache row with status=OK but to_history_id pointing to a
        // deleted (orphan) history row. The V28 JOIN cannot populate the
        // tuple, and the explicit RAISE EXCEPTION at the end fires.
        //
        // Note: V27 schema currently has a FK from cache.to_history_id
        // back to state_history.id, so simulating "orphan" requires
        // dropping the FK constraint or seeding the cache row before its
        // source. Easiest: insert source first, copy id, delete source,
        // insert cache. Or — easier — insert cache and then manually
        // null-out source_* in the cache while it's still nullable. We
        // do the second approach: insert cache normally, then NULL
        // source_* via direct UPDATE before running V28's NOT NULL
        // ALTER. But that bypasses the JOIN-backfill RAISE branch.
        //
        // Cleanest reproduction: drop the FK temporarily, leave cache
        // row with non-matching to_history_id, then run V28 and expect
        // PSQLException.
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV27(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant);
        UUID ghostHistoryId = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");

        // Drop the FK so we can insert a cache row pointing to a non-
        // existent history row.
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_software_diff_cache "
                + "DROP CONSTRAINT IF EXISTS swdc_to_history_fk");

        UUID cacheId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        // status=INSUFFICIENT_HISTORY: from_history_id null is allowed
        // by swdc_status_shape_ck; only to_history_id is required.
        // V27 CHECK swdc_non_ok_counts_zero_ck also requires all counts
        // = 0 for non-OK status.
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, computed_at) "
                        + "VALUES (?, ?, ?, NULL, ?, 'INSUFFICIENT_HISTORY', 0, 0, 0, ?)",
                cacheId, tenant, device, ghostHistoryId, now);

        // V28 should fail with the RAISE EXCEPTION because no source
        // row matches the orphan to_history_id.
        try {
            runV28(ds);
            assertThat(false).as("expected V28 to fail-loud on orphan to_history_id").isTrue();
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage())
                    .as("V28 RAISE EXCEPTION fired with the expected message")
                    .contains("V28 migration failed");
        }
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(JdbcTemplate jdbc, UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        String hostname = "host-" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now);
        return id;
    }

    private UUID insertSoftwareHistory(JdbcTemplate jdbc, UUID tenant, UUID device,
                                        Instant capturedAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp captured = Timestamp.from(capturedAt);
        Timestamp created = Timestamp.from(createdAt);
        String seed = id.toString().toLowerCase().replaceAll("[^0-9a-f]", "");
        String hashFull = (seed + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 0, ?, '[]'::jsonb, ?, ?)",
                id, tenant, device, hashFull, captured, created);
        return id;
    }

    private UUID insertOutdatedSnapshot(JdbcTemplate jdbc, UUID tenant, UUID device,
                                         Instant collectedAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp collected = Timestamp.from(collectedAt);
        Timestamp created = Timestamp.from(createdAt);
        String hash = id.toString().replace("-", "");
        hash = (hash + hash).substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 0, false, 100, 'winget', ?, ?, ?)",
                id, tenant, device, hash, collected, created);
        return id;
    }
}
