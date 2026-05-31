package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
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
 * BE — #1154 PR-2a regression guard: the device-grid {@code /query} native
 * SQL MUST resolve the device + snapshot tables when they live in a
 * NON-{@code public} schema — exactly like the live testai database
 * ({@code endpoint_admin_service}).
 *
 * <p><strong>Why this exists.</strong> The {@code DeviceGridQueryBuilder}
 * emits native SQL ({@code NamedParameterJdbcTemplate}), and native SQL is
 * NOT auto-qualified by Hibernate. The #342 live 500
 * ({@code relation ... does not exist}) proved that an unqualified
 * {@code FROM endpoint_devices} fails because the connection search_path
 * does not include {@code endpoint_admin_service}. This test reproduces the
 * live topology — schema {@code endpoint_admin_service} but NO
 * {@code currentSchema} on the JDBC URL, so search_path stays the PG default
 * {@code "$user", public} — so if any table in the builder is ever left
 * unqualified, the query throws here with the exact live error.
 *
 * <p>It also pins the functional contract: LATERAL latest-per-device picks
 * the newest snapshot, and a device with NO snapshot yields {@code null}
 * summary columns (authoritative "no snapshot", never a false absence).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceGridQuerySchemaQualificationPostgresIntegrationTest {

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
        // Deliberately NO currentSchema override: search_path stays the PG
        // default, exactly like live testai. Native unqualified SQL would not
        // find the tables (the bug this test guards).
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
    private EndpointDeviceHealthSnapshotRepository healthRepo;
    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository outdatedRepo;
    @Autowired
    private JdbcTemplate jdbc;

    private DeviceGridQueryService service() {
        // @DataJpaTest does not load @Service/@Component beans; wire the
        // grid query stack manually against the live (non-public-schema)
        // DataSource. Schema pinned to the live value the builder qualifies.
        DeviceGridQueryBuilder builder =
                new DeviceGridQueryBuilder(SCHEMA, 200, 200, 200);
        return new DeviceGridQueryService(new NamedParameterJdbcTemplate(jdbc.getDataSource()), builder);
    }

    @Test
    void gridQueryResolvesTablesInNonPublicSchema_latestPerDevice_andFailClosedNulls() {
        UUID tenant = UUID.randomUUID();
        UUID deviceWithSnaps = UUID.randomUUID();
        UUID deviceNoSnaps = UUID.randomUUID();

        // Raw SQL must name the schema explicitly (only Hibernate
        // auto-qualifies). Two devices: one with snapshots, one without.
        insertDevice(deviceWithSnaps, tenant, "host-with-snaps");
        insertDevice(deviceNoSnaps, tenant, "host-no-snaps");

        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        // Health: older then newer — latest memory_used_percent must win (90).
        saveHealth(tenant, deviceWithSnaps, t.minusSeconds(3600), 10);
        saveHealth(tenant, deviceWithSnaps, t, 90);
        // Outdated: older then newer — latest upgrade_count must win (7).
        saveOutdated(tenant, deviceWithSnaps, t.minusSeconds(3600), 2);
        saveOutdated(tenant, deviceWithSnaps, t, 7);

        // If any table reference were unqualified, this throws with the exact
        // live error "relation ... does not exist".
        DeviceGridQueryResponse resp = service().query(
                tenant, new DeviceGridQueryRequest(0, 50, null, null, null));

        assertThat(resp.lastRow()).isEqualTo(2L);
        assertThat(resp.rows()).hasSize(2);

        Map<String, Object> withSnaps = rowFor(resp.rows(), deviceWithSnaps);
        Map<String, Object> noSnaps = rowFor(resp.rows(), deviceNoSnaps);

        // Latest-per-device picked the newest snapshot of each kind.
        assertThat(asInt(withSnaps.get("health_memory_used_percent"))).isEqualTo(90);
        assertThat(asInt(withSnaps.get("outdated_upgrade_count"))).isEqualTo(7);
        assertThat(withSnaps.get("hostname")).isEqualTo("host-with-snaps");

        // Fail-closed: a device with no snapshot has NULL summary columns —
        // an authoritative "no snapshot", not a false absence.
        assertThat(noSnaps.get("health_memory_used_percent")).isNull();
        assertThat(noSnaps.get("outdated_upgrade_count")).isNull();
        assertThat(noSnaps.get("health_collected_at")).isNull();
        assertThat(noSnaps.get("hostname")).isEqualTo("host-no-snaps");
    }

    @Test
    void filterAndSortResolveInNonPublicSchema() {
        UUID tenant = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        insertDevice(d1, tenant, "alpha-host");
        insertDevice(d2, tenant, "beta-host");

        // Quick filter + sort exercise the WHERE/ORDER paths against the
        // non-public schema too (lower()/LIKE/ESCAPE must resolve).
        DeviceGridQueryResponse resp = service().query(tenant, new DeviceGridQueryRequest(
                0, 50,
                Map.of("hostname", Map.of("filterType", "text", "type", "contains", "filter", "alpha")),
                List.of(Map.of("colId", "hostname", "sort", "asc")),
                null));

        assertThat(resp.rows()).hasSize(1);
        assertThat(resp.rows().get(0).get("hostname")).isEqualTo("alpha-host");
        assertThat(resp.lastRow()).isEqualTo(1L);
    }

    @Test
    void deviceIdTextFilterResolvesViaUuidCast() {
        UUID tenant = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        insertDevice(d1, tenant, "cast-host-1");
        insertDevice(d2, tenant, "cast-host-2");

        // device_id text equals must resolve via d.id::text. lower(uuid) does
        // not exist in PG, so an un-cast text filter would 500 here.
        DeviceGridQueryResponse resp = service().query(tenant, new DeviceGridQueryRequest(
                0, 50,
                Map.of("device_id", Map.of("filterType", "text", "type", "equals", "filter", d1.toString())),
                null, null));

        assertThat(resp.rows()).hasSize(1);
        assertThat(resp.rows().get(0).get("device_id")).isEqualTo(d1);
    }

    // ───────────────────────── seed helpers ─────────────────────────

    private void insertDevice(UUID id, UUID tenant, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-05-30T09:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now);
    }

    private void saveHealth(UUID tenant, UUID device, Instant collectedAt, int memoryUsedPercent) {
        EndpointDeviceHealthSnapshot s = new EndpointDeviceHealthSnapshot();
        s.setTenantId(tenant);
        s.setDeviceId(device);
        s.setSchemaVersion((short) 1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setAnyLowDisk(false);
        s.setFixedDiskCount(1);
        s.setFixedDisksTruncated(false);
        s.setMaxFixedDisks(64);
        s.setMemoryUsedPercent((short) memoryUsedPercent);
        s.setMemoryHighPressure(false);
        s.setUptimeDays(10);
        s.setLongUptimeWarning(false);
        s.setSourceUsed("win32");
        s.setPayloadHashSha256("a".repeat(64));
        s.setCollectedAt(collectedAt);
        healthRepo.saveAndFlush(s);
    }

    private void saveOutdated(UUID tenant, UUID device, Instant collectedAt, int upgradeCount) {
        EndpointOutdatedSoftwareSnapshot s = new EndpointOutdatedSoftwareSnapshot();
        s.setTenantId(tenant);
        s.setDeviceId(device);
        s.setSchemaVersion((short) 1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setUpgradeCount(upgradeCount);
        s.setUpgradeTruncated(false);
        s.setMaxUpgrade(100);
        s.setSourceUsed("winget");
        s.setPayloadHashSha256("b".repeat(64));
        s.setCollectedAt(collectedAt);
        outdatedRepo.saveAndFlush(s);
    }

    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, UUID deviceId) {
        return rows.stream()
                .filter(r -> deviceId.equals(r.get("device_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("row not found for device " + deviceId));
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }
}
