package com.example.endpointadmin.service;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
 * Postgres-only verification of Faz 21.1 PR2b-ii canonical org_id write path
 * (Codex 019e8cc2 plan-time AGREE Option A, single PR, 7 entities).
 *
 * <p>This test exercises the entity-level invariant that PR2b-ii services
 * uphold: every new write supplies BOTH {@code tenant_id} and {@code org_id}
 * with the same UUID so that V30 CHECK
 * ({@code org_id IS NULL OR org_id = tenant_id}) passes without relying on
 * the V29 trigger fill silent compensation.
 *
 * <p>The actual service-mapper edits are reviewed in:
 * <ul>
 *   <li>EndpointDeviceService.createOrUpdateDevice (line ~35)</li>
 *   <li>EndpointSoftwareInventoryService.ingest history-write (line ~421)</li>
 *   <li>EndpointOutdatedSoftwareService.buildSnapshot (line ~264)</li>
 *   <li>EndpointAppControlService.buildSnapshot (line ~172)</li>
 *   <li>EndpointInstallAuditService.recordInstallAudit (line ~120)</li>
 *   <li>EndpointComplianceService.persistEvaluation (line ~424)</li>
 * </ul>
 *
 * <p>This test verifies the underlying contract: when an EndpointDevice is
 * persisted via JPA with both columns set explicitly to the same UUID, V30
 * passes and the row is readable with both columns equal — what every
 * downstream service consumer (BE-024c DiffCache, DeviceGridQueryBuilder
 * in PR2b-iii) will rely on.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CanonicalOrgIdWritePr2bIiPostgresIntegrationTest {

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void canonicalEndpointDeviceWrite_bothColumnsEqual_persistsViaCheck() {
        // Mirrors EndpointDeviceService.createOrUpdateDevice PR2b-ii edit:
        // setTenantId(tenantId); setOrgId(tenantId);
        UUID tenant = UUID.randomUUID();
        UUID hostnameSuffix = UUID.randomUUID();

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenant);
        device.setOrgId(tenant); // canonical PR2b-ii write
        device.setHostname("pr2b-ii-canonical-" + hostnameSuffix);
        device.setOsType(OsType.LINUX);
        device.setStatus(DeviceStatus.PENDING_ENROLLMENT);
        device.setEnrolledAt(Instant.now());

        entityManager.persist(device);
        entityManager.flush();

        Long bothColumnsEqualCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM endpoint_devices "
                        + "WHERE id = ? AND tenant_id = ? AND org_id = ? "
                        + "AND tenant_id = org_id",
                Long.class, device.getId(), tenant, tenant);
        assertThat(bothColumnsEqualCount)
                .as("PR2b-ii canonical write must persist both columns equal")
                .isEqualTo(1L);
    }

    @Test
    void canonicalWrite_v30CheckPasses_nullDualWriteAlsoAllowed() {
        // A row written with org_id NULL (e.g. legacy code path that hasn't
        // adopted PR2b-ii canonical write) is still permitted because V30
        // CHECK is `org_id IS NULL OR org_id = tenant_id`. V29 trigger then
        // fills org_id from tenant_id at INSERT, so the row ends up with
        // both columns populated equally.
        UUID tenant = UUID.randomUUID();
        UUID hostnameSuffix = UUID.randomUUID();

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenant);
        // intentionally NOT setting orgId — simulates a legacy code path
        device.setHostname("pr2b-ii-legacy-" + hostnameSuffix);
        device.setOsType(OsType.LINUX);
        device.setStatus(DeviceStatus.PENDING_ENROLLMENT);
        device.setEnrolledAt(Instant.now());

        entityManager.persist(device);
        entityManager.flush();

        UUID readOrgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE id = ?",
                UUID.class, device.getId());
        // V29 trigger filled org_id = tenant_id at INSERT.
        assertThat(readOrgId).isEqualTo(tenant);
    }
}
