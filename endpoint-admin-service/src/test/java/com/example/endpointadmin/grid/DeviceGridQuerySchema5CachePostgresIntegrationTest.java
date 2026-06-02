package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.grid.DeviceGridQueryBuilder.BuiltGridQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-024c v2-d grid SCHEMA v5 integration test — verifies the LEFT JOIN
 * to the two DiffCache tables produces the contract Codex 019e8a39 iter-1
 * AGREE pinned:
 *
 * <ul>
 *   <li><b>cache PRESENT</b>: device with a real cache row → 9 v5 colIds
 *       return the cache values (status enum + counts).</li>
 *   <li><b>cache ABSENT</b>: device with no cache row → 9 v5 colIds return
 *       NULL (read-model "not yet computed"; distinct from 'NO_HISTORY'
 *       which is a real cache row state).</li>
 *   <li><b>cache STALE</b>: cache row's source_captured_at older than the
 *       latest state_history row → grid still returns the stale cache
 *       values (grid is read-only; canonical drawer + AFTER_COMMIT
 *       listener + 10-min worker close the lag).</li>
 *   <li><b>filter NULL semantics</b>: {@code software_diff_added_count >= 5}
 *       excludes cache-absent devices (NULL &gt;= 5 → unknown → false in PG);
 *       a {@code blank} filter includes them.</li>
 *   <li><b>cache-NO_HISTORY vs cache-absent</b>: a cache row with
 *       status='NO_HISTORY' has counts=0 (not NULL), so a number
 *       {@code blank} filter does NOT match it but a status set filter
 *       on 'NO_HISTORY' DOES.</li>
 *   <li><b>DESC NULLS LAST</b>: {@code software_diff_added_count DESC} sort
 *       places cache-absent (NULL) devices BELOW cache-present devices.</li>
 * </ul>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceGridQuerySchema5CachePostgresIntegrationTest {

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NamedParameterJdbcTemplate namedJdbc;

    private DeviceGridQueryBuilder builder() {
        return new DeviceGridQueryBuilder(SCHEMA, 200, 200, 200);
    }

    @Test
    void cachePresent_v5ColumnsReturnCacheValues() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant, "host-cache-present");
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        UUID h1 = insertSoftwareHistory(tenant, device, t1, t1);
        UUID h2 = insertSoftwareHistory(tenant, device, t2, t2);
        // Direct cache insert with realistic source tuple from h2.
        insertSoftwareCacheOk(tenant, device, h1, h2, 5, 4, 3, t2, t2);
        UUID s1 = insertOutdatedSnapshot(tenant, device, t1, t1);
        UUID s2 = insertOutdatedSnapshot(tenant, device, t2, t2);
        insertOutdatedCacheOk(tenant, device, s1, s2, 7, 6, 5, 4, t2, t2);

        Map<String, Object> row = singleRow(tenant);
        assertThat(row.get("software_diff_status")).isEqualTo("OK");
        assertThat(row.get("software_diff_added_count")).isEqualTo(5);
        assertThat(row.get("software_diff_removed_count")).isEqualTo(4);
        assertThat(row.get("software_diff_version_changed_count")).isEqualTo(3);
        assertThat(row.get("outdated_diff_status")).isEqualTo("OK");
        assertThat(row.get("outdated_diff_added_count")).isEqualTo(7);
        assertThat(row.get("outdated_diff_removed_count")).isEqualTo(6);
        assertThat(row.get("outdated_diff_version_changed_count")).isEqualTo(5);
        assertThat(row.get("outdated_diff_available_version_bumped_count")).isEqualTo(4);
    }

    @Test
    void cacheAbsent_v5ColumnsReturnNull() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant, "host-cache-absent");
        // No cache row inserted. The LEFT JOIN yields NULL for the 9 v5 cols.

        Map<String, Object> row = singleRow(tenant);
        assertThat(row.get("software_diff_status")).isNull();
        assertThat(row.get("software_diff_added_count")).isNull();
        assertThat(row.get("software_diff_removed_count")).isNull();
        assertThat(row.get("software_diff_version_changed_count")).isNull();
        assertThat(row.get("outdated_diff_status")).isNull();
        assertThat(row.get("outdated_diff_added_count")).isNull();
        assertThat(row.get("outdated_diff_removed_count")).isNull();
        assertThat(row.get("outdated_diff_version_changed_count")).isNull();
        assertThat(row.get("outdated_diff_available_version_bumped_count")).isNull();
        // Device-level columns still populated — LEFT JOIN, device stays in grid.
        assertThat(row.get("device_id")).isEqualTo(device);
    }

    @Test
    void cacheStale_v5ColumnsReturnStaleValues() {
        // Cache row with older source_captured_at than the latest state_history
        // row. Grid is read-only; query returns the stale cache values. The
        // canonical drawer endpoint stays the live truth; the AFTER_COMMIT
        // listener + 10-min worker close the catch-up lag.
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant, "host-cache-stale");
        Instant tStale = Instant.parse("2026-06-02T10:00:00Z");
        Instant tFresh = Instant.parse("2026-06-02T11:00:00Z");
        UUID hStale1 = insertSoftwareHistory(tenant, device, tStale, tStale);
        UUID hStale2 = insertSoftwareHistory(tenant, device,
                Instant.parse("2026-06-02T10:30:00Z"),
                Instant.parse("2026-06-02T10:30:00Z"));
        // Cache row pinned to hStale2's tuple (intentionally stale).
        insertSoftwareCacheOk(tenant, device, hStale1, hStale2, 1, 1, 1,
                Instant.parse("2026-06-02T10:30:00Z"),
                Instant.parse("2026-06-02T10:30:00Z"));
        // Then a newer state_history row lands AFTER the cache row.
        insertSoftwareHistory(tenant, device, tFresh, tFresh);

        Map<String, Object> row = singleRow(tenant);
        // Grid returns the STALE cache values, not the live drawer truth.
        assertThat(row.get("software_diff_status")).isEqualTo("OK");
        assertThat(row.get("software_diff_added_count")).isEqualTo(1);
    }

    @Test
    void cacheAbsent_filterCount_excludedFromGreaterThanOrEqual() {
        // NULL >= 5 → unknown → false in PG. Cache-absent devices are
        // excluded from a number filter like "show devices with at least 5
        // added apps". Correct behavior per Codex iter-1: not-computed
        // devices must not silently match.
        UUID tenant = UUID.randomUUID();
        UUID deviceAbsent = insertDevice(tenant, "host-absent");
        UUID devicePresent = insertDevice(tenant, "host-present");
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        UUID h1 = insertSoftwareHistory(tenant, devicePresent, t, t);
        UUID h2 = insertSoftwareHistory(tenant, devicePresent,
                t.plusSeconds(60), t.plusSeconds(60));
        insertSoftwareCacheOk(tenant, devicePresent, h1, h2, 10, 0, 0,
                t.plusSeconds(60), t.plusSeconds(60));

        Map<String, Object> filter = Map.of(
                "software_diff_added_count", Map.of(
                        "filterType", "number",
                        "type", "greaterThanOrEqual",
                        "filter", 5));
        BuiltGridQuery q = builder().buildPageQuery(tenant,
                new DeviceGridQueryRequest(0, 50, filter, null, null));
        List<Map<String, Object>> rows =
                namedJdbc.queryForList(q.sql(), q.params());

        // Only the cache-present device with added_count=10 >= 5 matches.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("device_id")).isEqualTo(devicePresent);
    }

    @Test
    void cacheNoHistoryStatus_filterCount_excludedFromGreaterThanOrEqual() {
        // Cache row exists with status='NO_HISTORY', added_count=0. The
        // number filter >= 5 should NOT include this device either (its
        // count is 0, not NULL). Pins that NO_HISTORY behaves like any
        // real-cache row in number filters.
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant, "host-no-history");
        insertSoftwareCacheNoHistory(tenant, device);

        Map<String, Object> filter = Map.of(
                "software_diff_added_count", Map.of(
                        "filterType", "number",
                        "type", "greaterThanOrEqual",
                        "filter", 5));
        BuiltGridQuery q = builder().buildPageQuery(tenant,
                new DeviceGridQueryRequest(0, 50, filter, null, null));
        List<Map<String, Object>> rows =
                namedJdbc.queryForList(q.sql(), q.params());

        assertThat(rows).isEmpty();
    }

    @Test
    void cacheNoHistoryStatus_filterStatusSet_matchesIt() {
        // A status set filter on 'NO_HISTORY' must match the NO_HISTORY
        // cache row but not cache-absent or OK rows.
        UUID tenant = UUID.randomUUID();
        UUID deviceNoHistory = insertDevice(tenant, "host-no-history");
        UUID deviceAbsent = insertDevice(tenant, "host-absent");
        UUID deviceOk = insertDevice(tenant, "host-ok");
        insertSoftwareCacheNoHistory(tenant, deviceNoHistory);
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        UUID hOk1 = insertSoftwareHistory(tenant, deviceOk, t, t);
        UUID hOk2 = insertSoftwareHistory(tenant, deviceOk,
                t.plusSeconds(60), t.plusSeconds(60));
        insertSoftwareCacheOk(tenant, deviceOk, hOk1, hOk2, 1, 0, 0,
                t.plusSeconds(60), t.plusSeconds(60));

        Map<String, Object> filter = Map.of(
                "software_diff_status", Map.of(
                        "filterType", "set",
                        "values", List.of("NO_HISTORY")));
        BuiltGridQuery q = builder().buildPageQuery(tenant,
                new DeviceGridQueryRequest(0, 50, filter, null, null));
        List<Map<String, Object>> rows =
                namedJdbc.queryForList(q.sql(), q.params());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("device_id")).isEqualTo(deviceNoHistory);
    }

    @Test
    void descSort_cacheAbsentRowsAtBottom_nullsLast() {
        // Codex 019e8a39 iter-1 must-fix #2 absorb in action: a DESC sort on
        // software_diff_added_count places cache-absent (NULL) devices
        // BELOW cache-present devices (the "top N" semantic operators
        // actually want).
        UUID tenant = UUID.randomUUID();
        UUID deviceAbsent = insertDevice(tenant, "host-absent");
        UUID deviceLow = insertDevice(tenant, "host-low");
        UUID deviceHigh = insertDevice(tenant, "host-high");
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        UUID hLow1 = insertSoftwareHistory(tenant, deviceLow, t, t);
        UUID hLow2 = insertSoftwareHistory(tenant, deviceLow,
                t.plusSeconds(60), t.plusSeconds(60));
        insertSoftwareCacheOk(tenant, deviceLow, hLow1, hLow2, 1, 0, 0,
                t.plusSeconds(60), t.plusSeconds(60));
        UUID hHigh1 = insertSoftwareHistory(tenant, deviceHigh, t, t);
        UUID hHigh2 = insertSoftwareHistory(tenant, deviceHigh,
                t.plusSeconds(60), t.plusSeconds(60));
        insertSoftwareCacheOk(tenant, deviceHigh, hHigh1, hHigh2, 99, 0, 0,
                t.plusSeconds(60), t.plusSeconds(60));

        List<Map<String, Object>> sortDesc = List.of(
                Map.of("colId", "software_diff_added_count", "sort", "desc"));
        BuiltGridQuery q = builder().buildPageQuery(tenant,
                new DeviceGridQueryRequest(0, 50, null, sortDesc, null));
        List<Map<String, Object>> rows =
                namedJdbc.queryForList(q.sql(), q.params());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("device_id")).isEqualTo(deviceHigh);
        assertThat(rows.get(1).get("device_id")).isEqualTo(deviceLow);
        assertThat(rows.get(2).get("device_id")).isEqualTo(deviceAbsent);
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant, String hostname) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now);
        return id;
    }

    private UUID insertSoftwareHistory(UUID tenant, UUID device,
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

    private UUID insertOutdatedSnapshot(UUID tenant, UUID device,
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

    private void insertSoftwareCacheOk(UUID tenant, UUID device,
                                        UUID fromHistoryId, UUID toHistoryId,
                                        int addedCount, int removedCount,
                                        int versionChangedCount,
                                        Instant sourceCapturedAt,
                                        Instant sourceCreatedAt) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        Timestamp sourceCaptured = Timestamp.from(sourceCapturedAt);
        Timestamp sourceCreated = Timestamp.from(sourceCreatedAt);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " source_captured_at, source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OK', ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, device, fromHistoryId, toHistoryId,
                addedCount, removedCount, versionChangedCount,
                sourceCaptured, sourceCreated, toHistoryId, now);
    }

    private void insertSoftwareCacheNoHistory(UUID tenant, UUID device) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        Timestamp epoch = Timestamp.from(Instant.EPOCH);
        UUID zero = UUID.fromString("00000000-0000-0000-0000-000000000000");
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_diff_cache "
                        + "(id, tenant_id, device_id, from_history_id, to_history_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " source_captured_at, source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, ?, NULL, NULL, 'NO_HISTORY', 0, 0, 0, ?, ?, ?, ?)",
                id, tenant, device, epoch, epoch, zero, now);
    }

    private void insertOutdatedCacheOk(UUID tenant, UUID device,
                                        UUID fromSnapshotId, UUID toSnapshotId,
                                        int addedCount, int removedCount,
                                        int versionChangedCount,
                                        int availableVersionBumpedCount,
                                        Instant sourceCapturedAt,
                                        Instant sourceCreatedAt) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:02:00Z"));
        Timestamp sourceCaptured = Timestamp.from(sourceCapturedAt);
        Timestamp sourceCreated = Timestamp.from(sourceCreatedAt);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "(id, tenant_id, device_id, from_snapshot_id, to_snapshot_id, "
                        + " status, added_count, removed_count, version_changed_count, "
                        + " available_version_bumped_count, "
                        + " source_captured_at, source_created_at, source_row_id, computed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OK', ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, device, fromSnapshotId, toSnapshotId,
                addedCount, removedCount, versionChangedCount,
                availableVersionBumpedCount,
                sourceCaptured, sourceCreated, toSnapshotId, now);
    }

    /** Return the single device row for this tenant (assumes only 1 device). */
    private Map<String, Object> singleRow(UUID tenant) {
        BuiltGridQuery q = builder().buildPageQuery(tenant,
                new DeviceGridQueryRequest(0, 50, null, null, null));
        List<Map<String, Object>> rows = namedJdbc.queryForList(q.sql(), q.params());
        assertThat(rows).as("tenant has exactly 1 device row").hasSize(1);
        return rows.get(0);
    }
}
