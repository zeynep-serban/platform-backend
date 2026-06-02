package com.example.endpointadmin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
import com.example.endpointadmin.service.diff.OutdatedDiffSummary;
import com.example.endpointadmin.service.diff.SoftwareDiffSummary;
import java.sql.Timestamp;
import java.time.Instant;
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
 * BE-024c (Faz 22.5 P2-A v2-c-pre, Codex 019e88b5 iter-6 must_fix #2)
 * — service-level integration tests for the new summary cores.
 *
 * <p>Mocking the JPA entities for unit-level tests is awkward (the model
 * classes lack public id setters); against a real Postgres container we
 * seed history/snapshots through raw JDBC and assert the {@code summarize()}
 * count-only walk surfaces the same status semantics as the canonical
 * drawer-side {@code diffLatest()} path (Codex 019e88b5 iter-6 must_fix
 * #1 drawer parity).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EndpointSoftwareInventoryDiffService.class,
         EndpointOutdatedSoftwareDiffService.class})
class SummarizePostgresIntegrationTest {

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
    @Autowired
    private EndpointSoftwareInventoryStateHistoryRepository historyRepository;
    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository outdatedRepository;

    private EndpointSoftwareInventoryDiffService softwareService;
    private EndpointOutdatedSoftwareDiffService outdatedService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        softwareService = new EndpointSoftwareInventoryDiffService(historyRepository);
        outdatedService = new EndpointOutdatedSoftwareDiffService(outdatedRepository);
    }

    // ═════════════════════ software ═════════════════════════════

    @Test
    void software_noCaptures_returnsNoHistory_withBothIdsNull() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        SoftwareDiffSummary s = softwareService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.NO_HISTORY);
        assertThat(s.fromHistoryId()).isNull();
        assertThat(s.toHistoryId()).isNull();
    }

    @Test
    void software_oneCapture_returnsInsufficientHistory_withToIdSet() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"),
                "hash-a", 0, "[]");

        SoftwareDiffSummary s = softwareService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.INSUFFICIENT_HISTORY);
        assertThat(s.fromHistoryId()).isNull();
        assertThat(s.toHistoryId()).isEqualTo(h1);
    }

    @Test
    void software_identicalDigestHash_returnsNoChange_withBothIdsSet() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"),
                "shared-hash", 1, "[{\"appKey\":\"pkg-1\",\"version\":\"1.0\"}]");
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"),
                "shared-hash", 1, "[{\"appKey\":\"pkg-1\",\"version\":\"1.0\"}]");

        SoftwareDiffSummary s = softwareService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.NO_CHANGE);
        assertThat(s.fromHistoryId()).isEqualTo(h1);
        assertThat(s.toHistoryId()).isEqualTo(h2);
    }

    @Test
    void software_addedRemovedVersionChanged_returnsOk_withCounts() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"),
                "hash-a", 2,
                "[{\"appKey\":\"pkg-1\",\"version\":\"1.0\"},"
                + "{\"appKey\":\"pkg-2\",\"version\":\"1.0\"}]");
        // h2: pkg-1 v1.1 (changed) + pkg-3 v1.0 (added); pkg-2 removed.
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"),
                "hash-b", 2,
                "[{\"appKey\":\"pkg-1\",\"version\":\"1.1\"},"
                + "{\"appKey\":\"pkg-3\",\"version\":\"1.0\"}]");

        SoftwareDiffSummary s = softwareService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.OK);
        assertThat(s.fromHistoryId()).isEqualTo(h1);
        assertThat(s.toHistoryId()).isEqualTo(h2);
        assertThat(s.addedCount()).isEqualTo(1);
        assertThat(s.removedCount()).isEqualTo(1);
        assertThat(s.versionChangedCount()).isEqualTo(1);
    }

    @Test
    void software_digestMismatchButZeroKeyDelta_returnsOkZeroCounts_preservingDrawerParity() {
        // Codex 019e88b5 iter-6 must_fix #1: e.g. only display_name re-titled
        // — KEY_VERSION unchanged. computeDiff() returns OK with empty lists;
        // summary MUST also return OK with zero counts (NOT NO_CHANGE), or
        // v2-d grid would show NO_CHANGE while the drawer shows OK for the
        // same source pair.
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID h1 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:00:00Z"),
                "hash-with-old-titles", 1,
                "[{\"appKey\":\"pkg-1\",\"version\":\"1.0\","
                + "\"displayName\":\"Old Title\"}]");
        UUID h2 = insertSoftwareHistory(tenant, device, Instant.parse("2026-06-02T10:01:00Z"),
                "hash-with-new-titles", 1,
                "[{\"appKey\":\"pkg-1\",\"version\":\"1.0\","
                + "\"displayName\":\"New Title\"}]");

        SoftwareDiffSummary s = softwareService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.OK);
        assertThat(s.fromHistoryId()).isEqualTo(h1);
        assertThat(s.toHistoryId()).isEqualTo(h2);
        assertThat(s.addedCount()).isZero();
        assertThat(s.removedCount()).isZero();
        assertThat(s.versionChangedCount()).isZero();
    }

    @Test
    void software_tenantIsolation_rejectsCrossTenantHistoryAsNoHistory() {
        // Tenant A has history; Tenant B with the same device id should NOT
        // see Tenant A's data (the unknown/cross-tenant answer is NO_HISTORY).
        UUID tenantA = UUID.randomUUID();
        UUID device = insertDevice(tenantA);
        insertSoftwareHistory(tenantA, device, Instant.parse("2026-06-02T10:00:00Z"),
                "hash-a", 0, "[]");

        UUID tenantB = UUID.randomUUID();
        SoftwareDiffSummary s = softwareService.summarize(tenantB, device);

        assertThat(s.status()).isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.NO_HISTORY);
    }

    // ═════════════════════ outdated ═════════════════════════════

    @Test
    void outdated_noSnapshots_returnsNoHistory_withBothIdsNull() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);

        OutdatedDiffSummary s = outdatedService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.NO_HISTORY);
        assertThat(s.fromSnapshotId()).isNull();
        assertThat(s.toSnapshotId()).isNull();
    }

    @Test
    void outdated_oneSnapshot_returnsInsufficientHistory_withToIdSet() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));

        OutdatedDiffSummary s = outdatedService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.INSUFFICIENT_HISTORY);
        assertThat(s.fromSnapshotId()).isNull();
        assertThat(s.toSnapshotId()).isEqualTo(s1);
    }

    @Test
    void outdated_twoEmptySnapshots_returnsNoChange_withBothIdsSet() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        UUID s1 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:00:00Z"));
        UUID s2 = insertOutdatedSnapshot(tenant, device, Instant.parse("2026-06-02T10:01:00Z"));

        OutdatedDiffSummary s = outdatedService.summarize(tenant, device);

        assertThat(s.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.NO_CHANGE);
        assertThat(s.fromSnapshotId()).isEqualTo(s1);
        assertThat(s.toSnapshotId()).isEqualTo(s2);
        assertThat(s.addedCount()).isZero();
        assertThat(s.removedCount()).isZero();
        assertThat(s.versionChangedCount()).isZero();
        assertThat(s.availableVersionBumpedCount()).isZero();
    }

    @Test
    void outdated_tenantIsolation_rejectsCrossTenantSnapshotAsNoHistory() {
        UUID tenantA = UUID.randomUUID();
        UUID device = insertDevice(tenantA);
        insertOutdatedSnapshot(tenantA, device, Instant.parse("2026-06-02T10:00:00Z"));

        UUID tenantB = UUID.randomUUID();
        OutdatedDiffSummary s = outdatedService.summarize(tenantB, device);

        assertThat(s.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.NO_HISTORY);
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

    private UUID insertSoftwareHistory(UUID tenant, UUID device, Instant capturedAt,
                                        String hash, int appCount, String appsDigestJson) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(capturedAt);
        // hash check is lowercase hex 64-char; pad with deterministic
        // hex digits and lowercase the seed.
        String seed = hash.toLowerCase().replaceAll("[^0-9a-f]", "");
        String hashFull = (seed + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, 1, "
                        + "        ?, ?, ?::jsonb, "
                        + "        ?, ?)",
                id, tenant, device, appCount, hashFull, appsDigestJson, ts, ts);
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
}
