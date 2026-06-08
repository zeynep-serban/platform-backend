package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * Faz 22.7 COMPLETED-promotion gate (Codex {@code 019ea95d} finding 6): tenant
 * cross-leak guard for the compliance-gap aggregate query. The native query in
 * {@link ComplianceGapRepository} filters every CTE + the device root by
 * {@code tenant_id = :tenantId} and joins {@code s.tenant_id = d.tenant_id}; this
 * IT proves the boundary holds against a real Postgres + Flyway schema: a device
 * with an RDP-enabled startup snapshot under tenant A is visible to tenant A's
 * query and invisible to tenant B's query (no existence leak, fail-closed).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ComplianceGapRepository.class)
class ComplianceGapRepositoryTenantIsolationPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final Instant COLLECTED_AT = Instant.parse("2026-06-09T10:00:00Z");
    private static final Instant FRESHNESS_THRESHOLD = Instant.parse("2026-06-08T10:00:00Z");
    private static final Set<String> RDP = Set.of("rdp_enabled");
    private static final Set<String> PENDING = Set.of("pending_security_updates");

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
    private ComplianceGapRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rdpGap_isVisibleToOwningTenant_andInvisibleToOtherTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();

        insertDevice(deviceA, tenantA, "tenantA-rdp-device");
        insertDevice(deviceB, tenantB, "tenantB-clean-device");
        insertStartupExposureRdpEnabled(deviceA, tenantA);

        // Tenant A sees its own RDP gap.
        List<Object[]> tenantARows =
                repository.findGapDevices(tenantA, FRESHNESS_THRESHOLD, RDP, 50, 0);
        assertThat(tenantARows)
                .as("tenant A must see its own RDP-enabled device")
                .hasSize(1);
        assertThat(String.valueOf(tenantARows.get(0)[0])).isEqualTo(deviceA.toString());
        assertThat(repository.countGapDevices(tenantA, FRESHNESS_THRESHOLD, RDP)).isEqualTo(1L);

        // Tenant B MUST NOT see tenant A's device or snapshot (cross-leak guard).
        List<Object[]> tenantBRows =
                repository.findGapDevices(tenantB, FRESHNESS_THRESHOLD, RDP, 50, 0);
        assertThat(tenantBRows)
                .as("tenant B must NOT see tenant A's RDP gap — tenant boundary fail-closed")
                .isEmpty();
        assertThat(repository.countGapDevices(tenantB, FRESHNESS_THRESHOLD, RDP)).isZero();
    }

    @Test
    void pendingUpdatesGap_isVisibleToOwningTenant_andInvisibleToOtherTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();

        insertDevice(deviceA, tenantA, "tenantA-pending-device");
        insertDevice(deviceB, tenantB, "tenantB-clean-device");
        insertHotfixPendingUpdates(deviceA, tenantA);

        // Tenant A sees its own pending-security-updates gap (hotfix path).
        List<Object[]> tenantARows =
                repository.findGapDevices(tenantA, FRESHNESS_THRESHOLD, PENDING, 50, 0);
        assertThat(tenantARows)
                .as("tenant A must see its own pending-security-updates device")
                .hasSize(1);
        assertThat(String.valueOf(tenantARows.get(0)[0])).isEqualTo(deviceA.toString());
        assertThat(repository.countGapDevices(tenantA, FRESHNESS_THRESHOLD, PENDING)).isEqualTo(1L);

        // Tenant B MUST NOT see tenant A's hotfix snapshot (cross-leak guard).
        List<Object[]> tenantBRows =
                repository.findGapDevices(tenantB, FRESHNESS_THRESHOLD, PENDING, 50, 0);
        assertThat(tenantBRows)
                .as("tenant B must NOT see tenant A's pending gap — tenant boundary fail-closed")
                .isEmpty();
        assertThat(repository.countGapDevices(tenantB, FRESHNESS_THRESHOLD, PENDING)).isZero();
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDevice(UUID id, UUID tenant, String hostname) {
        Timestamp now = Timestamp.from(COLLECTED_AT);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, tenant, hostname, now, now);
    }

    /**
     * Seeds an RDP-enabled startup-exposure snapshot. {@code org_id} is left to
     * the V42 compat trigger ({@code endpoint_se_snap_org_id_compat}) which fills
     * it from {@code tenant_id}. {@code created_at} defaults to now().
     */
    private void insertStartupExposureRdpEnabled(UUID deviceId, UUID tenant) {
        Timestamp collected = Timestamp.from(COLLECTED_AT);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_startup_exposure_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, rdp_enabled, windows_firewall_event_log_enabled, "
                        + " probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, TRUE, TRUE, TRUE, FALSE, 10, ?, ?)",
                UUID.randomUUID(), tenant, deviceId,
                "0000000000000000000000000000000000000000000000000000000000000000",
                collected);
    }

    /**
     * Seeds a hotfix-posture snapshot with {@code pending_total_count > 0}
     * (triggers the PENDING_SECURITY_UPDATES gap). Source-used + service-state
     * values use the V22 CHECK-constrained enums; {@code org_id} is filled by
     * the V43 compat trigger; redacted_payload / probe_errors / created_at /
     * updated_at / version use their column defaults.
     */
    private void insertHotfixPendingUpdates(UUID deviceId, UUID tenant) {
        Timestamp collected = Timestamp.from(COLLECTED_AT);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hotfix_posture_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, probe_complete, "
                        + " installed_count, max_installed, installed_truncated, "
                        + " pending_total_count, max_pending, pending_truncated, "
                        + " installed_source_used, pending_source_used, health_source_used, "
                        + " payload_hash_sha256, wua_service_state, bits_service_state, collected_at) "
                        + "VALUES (?, ?, ?, 1, TRUE, TRUE, 0, 256, FALSE, 5, 256, FALSE, "
                        + " 'wua', 'wua', 'service', ?, 'RUNNING', 'RUNNING', ?)",
                UUID.randomUUID(), tenant, deviceId,
                "0000000000000000000000000000000000000000000000000000000000000000",
                collected);
    }
}
