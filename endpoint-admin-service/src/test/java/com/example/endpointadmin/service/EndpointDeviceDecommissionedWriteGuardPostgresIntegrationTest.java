package com.example.endpointadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.audit.AuditIntegrityVerifier;
import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestCreate;
import com.example.endpointadmin.dto.v1.admin.CreateAgentUpdateRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.CreateLocalPasswordChangeRequest;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenRequest;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import com.example.endpointadmin.security.EnrollmentTokenGenerator;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Codex 019ea789 iter-2 regression set — EVERY device-work create path must
 * fail-closed with 409 on a DECOMMISSIONED device, via the shared
 * {@link EndpointDeviceWriteGuard#loadActiveForUpdate} (PESSIMISTIC_WRITE lock
 * + status check).
 *
 * <p>These "trivial guard" bypasses are exactly what escaped the suite at iter-1
 * (the guards existed but were plain reads with no test pinning them). Each test
 * here exercises the real service against a real Postgres so the guard cannot
 * silently regress.
 *
 * <p>The uninstall {@code approve} test additionally pins the LOCK-ORDER fix:
 * it calls approve with a NON-existent request id and asserts {@code 409}
 * (device decommissioned), not {@code 404} (request not found). Only the
 * device→request order (device locked + checked FIRST) produces 409 here; the
 * old request-first order would have surfaced 404.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointDeviceLifecycleService.class,
        EndpointDeviceLifecycleCascade.class,
        EndpointDeviceService.class,
        EndpointAdminCommandService.class,
        EndpointMaintenanceTokenService.class,
        EndpointUninstallService.class,
        EndpointAuditService.class,
        EndpointInstallPreflightService.class,
        EndpointCommandSecretService.class,
        AesGcmDeviceSecretProtector.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class,
        EnrollmentTokenGenerator.class,
        EnrollmentTokenHasher.class
})
class EndpointDeviceDecommissionedWriteGuardPostgresIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("endpoint-admin.secrets.encryption-key", () -> "test-endpoint-command-secret-key");
        // EnrollmentTokenHasher (pulled in via EndpointMaintenanceTokenService)
        // requires a pepper outside local/dev/test profiles; this slice sets no
        // active profile, so provide one explicitly.
        registry.add("endpoint-admin.enrollment.token-pepper", () -> "test-enrollment-pepper");
        // Uninstall surface defaults to dark mode (503); enable it so the
        // propose/approve DECOMMISSIONED guards are reached, not short-circuited.
        registry.add("endpoint-admin.uninstall.enabled", () -> "true");
    }

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-ea78-2222-ea78-aaaaaaaaaaaa");
    private static final String SUBJECT = "admin@example.com";

    @Autowired private EndpointDeviceLifecycleService lifecycleService;
    @Autowired private EndpointAdminCommandService commandService;
    @Autowired private EndpointMaintenanceTokenService maintenanceTokenService;
    @Autowired private EndpointUninstallService uninstallService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, SUBJECT);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ───────────────────────── command surfaces ─────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_genericCommandCreate_isConflict() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> commandService.createCommand(context(), deviceId,
                new CreateEndpointCommandRequest(CommandType.COLLECT_INVENTORY,
                        "k-" + UUID.randomUUID(), "r", null, 100, 3, null, null, null)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_agentUpdateCreate_isConflict() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> commandService.createAgentUpdate(context(), deviceId,
                new CreateAgentUpdateRequest("rel-" + UUID.randomUUID(), null,
                        "ship update", null, null, null)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_installCreate_isConflict() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> commandService.createInstall(context(), deviceId,
                new CreateInstallRequest("7zip", null, "install", null, null, null)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_localPasswordChange_isConflict_andMintsNoSecret() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> commandService.createLocalPasswordChange(context(), deviceId,
                new CreateLocalPasswordChangeRequest("localadmin", null, "rotate", null, null)));

        // The one-time password is generated + persisted ONLY after the device
        // load (which 409s). No create path in this class ever succeeds, so the
        // secret table must be empty — proving the guard fires before any secret
        // material is minted.
        Integer secrets = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_command_secrets", Integer.class);
        assertThat(secrets).isZero();
    }

    // ───────────────────────── maintenance token ─────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_maintenanceTokenCreate_isConflict() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> maintenanceTokenService.createToken(context(), deviceId,
                new CreateMaintenanceTokenRequest(MaintenanceAction.STOP_AGENT, "support", 60)));
    }

    // ───────────────────────── uninstall propose/approve ─────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_uninstallPropose_isConflict() {
        UUID deviceId = seedDecommissionedDevice();
        assertConflict(() -> uninstallService.propose(context(), deviceId,
                new AdminUninstallRequestCreate("7zip", null, "remove")));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommissionedDevice_uninstallApprove_isConflict_deviceLockedFirst() {
        UUID deviceId = seedDecommissionedDevice();
        // Non-existent request id on purpose: device→request lock order means the
        // DECOMMISSIONED device is detected (409) BEFORE the request is even
        // looked up. The pre-fix request-first order would have returned 404.
        assertConflict(() -> uninstallService.approve(context(), deviceId,
                UUID.randomUUID(), null));
    }

    // ───────────────────────── helpers ─────────────────────────

    private void assertConflict(Runnable call) {
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> call.run()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    private UUID seedDecommissionedDevice() {
        UUID deviceId = seedEnrolledOnlineDevice();
        tx().executeWithoutResult(s ->
                lifecycleService.decommission(TENANT, SUBJECT, deviceId, "retire"));
        return deviceId;
    }

    private UUID seedEnrolledOnlineDevice() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, status, "
                        + " os_type, os_version, agent_version, enrolled_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ONLINE', 'WINDOWS', 'Windows 11', '1.0.0', ?, ?, ?, 0)",
                id, TENANT, TENANT, "host-" + id, "fp-" + id, now, now, now);
        return id;
    }
}
