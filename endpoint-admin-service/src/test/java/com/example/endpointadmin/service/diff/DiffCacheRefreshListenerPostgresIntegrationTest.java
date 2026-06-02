package com.example.endpointadmin.service.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;

/**
 * BE-024c v2-c-pre-2-C-C Category C — AFTER_COMMIT listener consistency
 * PG IT (Codex 019e8a09 iter-1 deferred coverage, agreed as pre-rollout
 * gate for v2-d grid SCHEMA v5).
 *
 * <p>Verifies that {@link DiffCacheRefreshListener}'s
 * {@link org.springframework.transaction.event.TransactionalEventListener}
 * fires on the AFTER_COMMIT phase and {@link DiffCacheRefreshService} in
 * its REQUIRES_NEW transaction does the summarize + upsert correctly.
 *
 * <p>Without @SpringBootTest infrastructure, this uses @DataJpaTest +
 * explicit @Import of the listener/service/dependencies + a
 * TransactionTemplate with PROPAGATION_REQUIRES_NEW around the event
 * publish so the inner transaction actually commits and triggers the
 * AFTER_COMMIT listener. The test's outer @DataJpaTest @Transactional is
 * suspended during the REQUIRES_NEW block; @Rollback(false) keeps the
 * test transaction itself committed at end so any cleanup data persists
 * for the next sweep.
 *
 * <h2>Test categories</h2>
 * <ul>
 *   <li><b>happy path</b> — publishing the event commits, listener fires,
 *       REQUIRES_NEW refresh runs, cache row populated with correct
 *       tuple from the latest history.</li>
 *   <li><b>listener catch-log boundary</b> — when refresh service throws
 *       (e.g. ghost device id with no endpoint_devices row), the
 *       listener's catch swallows so the ingest path is unaffected; no
 *       exception propagates back to the publisher.</li>
 *   <li><b>both types fire independently</b> — SOFTWARE event publish
 *       updates only software cache; OUTDATED event publish updates only
 *       outdated cache.</li>
 * </ul>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiffCacheRefreshListener.class, DiffCacheRefreshService.class,
        DiffCacheService.class,
        EndpointSoftwareInventoryDiffService.class,
        EndpointOutdatedSoftwareDiffService.class})
@Rollback(false)
class DiffCacheRefreshListenerPostgresIntegrationTest {

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

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    /**
     * Publish the event inside a REQUIRES_NEW transaction so it commits
     * and the AFTER_COMMIT listener actually fires.
     */
    private void publishWithCommit(DiffCacheRefreshRequested event) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private void seedCommit(Runnable seed) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> seed.run());
    }

    @Test
    void software_eventPublish_listenerFiresAfterCommit_cachePopulated() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[3];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            holder[1] = insertSoftwareHistory(tenant, holder[0], t1, t1);
            holder[2] = insertSoftwareHistory(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];
        UUID h2 = holder[2];

        // Before publish: no cache row.
        assertThat(readSoftwareCacheRow(tenant, device)).as("no cache row pre-publish").isNull();

        // Publish event. Listener should fire AFTER_COMMIT and refresh
        // service should populate the cache row.
        publishWithCommit(new DiffCacheRefreshRequested(tenant, device, DiffType.SOFTWARE));

        // After publish: cache row exists with the LATEST committed
        // source tuple — exact tuple assertions per Codex 019e8a25
        // iter-1 low absorb (don't just check existence).
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row).as("listener populated cache row").isNotNull();
        assertThat(row.get("status")).isIn("OK", "NO_CHANGE");
        assertThat(row.get("to_history_id"))
                .as("to_history_id points to latest source row h2").isEqualTo(h2);
        assertThat(row.get("source_captured_at"))
                .as("source_captured_at matches h2.captured_at")
                .isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_created_at"))
                .as("source_created_at matches h2.created_at")
                .isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_row_id"))
                .as("source_row_id matches h2.id").isEqualTo(h2);
    }

    @Test
    void software_eventPublishedThenRolledBack_listenerDoesNotFire() {
        // Codex 019e8a25 iter-1 nice-to-have counter-test: AFTER_COMMIT
        // phase must NOT fire when the outer transaction rolls back.
        // Proves the listener is really AFTER_COMMIT-bound (not a
        // synchronous @EventListener that fires inline on publish).
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            insertSoftwareHistory(tenant, holder[0], t1, t1);
            insertSoftwareHistory(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];

        // Publish then setRollbackOnly — outer tx rolls back, AFTER_COMMIT
        // should NOT fire, cache row should NOT be populated.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new DiffCacheRefreshRequested(tenant, device, DiffType.SOFTWARE));
            status.setRollbackOnly();
        });

        assertThat(readSoftwareCacheRow(tenant, device))
                .as("rolled-back publish must NOT populate cache (AFTER_COMMIT contract)")
                .isNull();
    }

    @Test
    void outdated_eventPublish_listenerFiresAfterCommit_cachePopulated() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[3];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            holder[1] = insertOutdatedSnapshot(tenant, holder[0], t1, t1);
            holder[2] = insertOutdatedSnapshot(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];
        UUID s2 = holder[2];

        assertThat(readOutdatedCacheRow(tenant, device)).as("no outdated cache row pre-publish").isNull();

        publishWithCommit(new DiffCacheRefreshRequested(tenant, device, DiffType.OUTDATED));

        // Codex 019e8a25 iter-1 low absorb: assert exact source tuple
        // (latest committed snapshot s2), not just row existence.
        Map<String, Object> row = readOutdatedCacheRow(tenant, device);
        assertThat(row).as("listener populated outdated cache row").isNotNull();
        assertThat(row.get("to_snapshot_id"))
                .as("to_snapshot_id points to latest source s2").isEqualTo(s2);
        assertThat(row.get("source_captured_at"))
                .as("source_captured_at matches s2.collected_at")
                .isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_created_at"))
                .as("source_created_at matches s2.created_at")
                .isEqualTo(Timestamp.from(t2));
        assertThat(row.get("source_row_id"))
                .as("source_row_id matches s2.id").isEqualTo(s2);
    }

    @Test
    void software_eventPublish_typeBoundary_doesNotTouchOutdatedCache() {
        // Publishing a SOFTWARE event must NOT populate outdated cache
        // (and vice versa). Verifies the switch in DiffCacheRefreshService.
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            // Seed BOTH source types so we know it's the switch that
            // chooses, not just data availability.
            insertSoftwareHistory(tenant, holder[0], t1, t1);
            insertSoftwareHistory(tenant, holder[0], t2, t2);
            insertOutdatedSnapshot(tenant, holder[0], t1, t1);
            insertOutdatedSnapshot(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];

        publishWithCommit(new DiffCacheRefreshRequested(tenant, device, DiffType.SOFTWARE));

        assertThat(readSoftwareCacheRow(tenant, device))
                .as("software cache populated").isNotNull();
        assertThat(readOutdatedCacheRow(tenant, device))
                .as("outdated cache UNTOUCHED — SOFTWARE event must not cross types").isNull();
    }

    @Test
    void listenerExceptionSwallowed_publishCommitsSuccessfully() {
        // Listener has a catch around refresh service. When refresh
        // throws (ghost device with no endpoint_devices row → swdc_device_fk
        // violation), the listener swallows so the publish path is
        // unaffected. Verify by publishing in a try/catch — no exception
        // should propagate.
        UUID tenant = UUID.randomUUID();
        UUID ghostDevice = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0");

        // No seed — endpoint_devices is empty for this ghost device.
        try {
            publishWithCommit(new DiffCacheRefreshRequested(tenant, ghostDevice, DiffType.SOFTWARE));
        } catch (RuntimeException ex) {
            assertThat(false).as("listener should swallow refresh exception, but got %s", ex)
                    .isTrue();
        }

        // No cache row was written (the upsert hit FK and rolled back).
        assertThat(readSoftwareCacheRow(tenant, ghostDevice))
                .as("no cache row for ghost device").isNull();
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        String hostname = "host-" + id.toString().substring(0, 8);
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

    private Map<String, Object> readSoftwareCacheRow(UUID tenant, UUID device) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, from_history_id, to_history_id, added_count, "
                + "removed_count, version_changed_count, source_captured_at, "
                + "source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_software_diff_cache "
                + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> readOutdatedCacheRow(UUID tenant, UUID device) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, from_snapshot_id, to_snapshot_id, "
                + "source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
