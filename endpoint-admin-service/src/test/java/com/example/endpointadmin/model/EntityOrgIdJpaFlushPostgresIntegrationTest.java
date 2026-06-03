package com.example.endpointadmin.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-only verification that JPA setter→flush path actually persists
 * orgId on the new entity fields introduced in PR2b-i, not just that the
 * DB-level shape is correct (covered by
 * {@link EntityOrgIdCompatFoundationPostgresIntegrationTest}).
 *
 * <p>Codex iter-1 absorb (thread 019e8cac):
 * <ul>
 *   <li>EndpointDevice.setOrgId() + flush → DB row has org_id populated</li>
 *   <li>EndpointOutdatedSoftwarePackage with updatable=false constraint:
 *       insert via setSnapshot() / onPersist() mirrors orgId from parent
 *       snapshot's effective-org, even though setOrgId() during UPDATE is
 *       silently ignored by Hibernate</li>
 * </ul>
 *
 * <p>This is the JPA-side "foundation truth" PR2b-ii (canonical write
 * path) and PR2b-iii (repository COALESCE) will build on.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityOrgIdJpaFlushPostgresIntegrationTest {

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

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void endpointDeviceSetOrgId_flushedToDatabase() {
        // Canonical write path the service layer (PR2b-ii) will adopt:
        // setOrgId() during persist; flush should write the column.
        UUID tenant = UUID.randomUUID();
        UUID hostnameSuffix = UUID.randomUUID();

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenant);
        device.setOrgId(tenant); // canonical equal write
        device.setHostname("jpa-flush-test-" + hostnameSuffix);
        device.setOsType(OsType.LINUX);
        device.setStatus(DeviceStatus.PENDING_ENROLLMENT);

        EndpointDevice saved = testEntityManager.persistAndFlush(device);

        UUID readOrgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE id = ?",
                UUID.class, saved.getId());
        UUID readTenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM endpoint_devices WHERE id = ?",
                UUID.class, saved.getId());

        assertThat(readOrgId)
                .as("setOrgId() then flush must persist org_id column")
                .isEqualTo(tenant);
        assertThat(readTenantId)
                .as("tenant_id remains populated alongside org_id")
                .isEqualTo(tenant);
        assertThat(saved.getEffectiveOrgId())
                .as("getEffectiveOrgId() returns orgId when populated")
                .isEqualTo(tenant);
    }

    @Test
    void getEffectiveOrgIdHelper_pureEntityFallsBackToTenantId() {
        // Pure entity behavior — no DB round-trip, no V29 trigger. Codex
        // iter-2 absorb: verifies the in-memory contract that
        // getEffectiveOrgId() returns tenantId when orgId is null.
        UUID tenant = UUID.randomUUID();
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenant);
        // orgId intentionally left null
        assertThat(device.getOrgId()).isNull();
        assertThat(device.getEffectiveOrgId())
                .as("Pure-entity fallback: orgId NULL → returns tenantId")
                .isEqualTo(tenant);
    }

    @Test
    void endpointDeviceLegacyTriggerFillsOrgIdOnReRead() {
        // Service code that has NOT yet been migrated to canonical write
        // (PR2b-ii pending) only sets tenantId. JPA persists with orgId
        // null; V29 trigger fills it at INSERT; getEffectiveOrgId() helper
        // returns the populated value either way.
        UUID tenant = UUID.randomUUID();
        UUID hostnameSuffix = UUID.randomUUID();

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenant);
        // intentionally NOT setting orgId
        device.setHostname("jpa-legacy-" + hostnameSuffix);
        device.setOsType(OsType.LINUX);
        device.setStatus(DeviceStatus.PENDING_ENROLLMENT);

        EndpointDevice saved = testEntityManager.persistAndFlush(device);
        testEntityManager.clear(); // detach from session, force re-read

        EndpointDevice reread = testEntityManager.find(EndpointDevice.class, saved.getId());

        // V29 trigger filled org_id = tenant_id at INSERT time; entity
        // reread sees both populated.
        assertThat(reread.getOrgId())
                .as("V29 trigger fills org_id from tenant_id when caller leaves it null")
                .isEqualTo(tenant);
        assertThat(reread.getEffectiveOrgId()).isEqualTo(tenant);
    }

    /*
     * NOTE on EndpointOutdatedSoftwarePackage child-table coverage:
     *
     * The Codex iter-1 absorbed onPersist() + setSnapshot() mirror logic
     * (orgId from snapshot.getEffectiveOrgId()) is verified by code review
     * here in PR2b-i. A full JPA flush integration test for the package
     * persist path requires seeding a valid parent snapshot with the
     * snapshot table's full NOT NULL surface (schema_version, supported,
     * probe_complete, upgrade_count, upgrade_truncated, max_upgrade,
     * source_used, payload_hash_sha256, redacted_payload jsonb,
     * probe_errors jsonb). That fixture belongs with PR2b-ii canonical
     * write path tests where the snapshot service path itself is being
     * migrated to set both tenant_id + org_id. PR2b-i ships the
     * lifecycle code so the next test can verify it observationally.
     */
}
