package com.example.endpointadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.audit.AuditIntegrityVerifier;
import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceLifecycleAction;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceLifecycleAuditRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
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
 * Service-level decommission/reactivate lifecycle (Codex 019ea789). Real PG +
 * the genuine audit chain (mirrors EndpointInstallCommandAuditPostgresIntegration
 * Test): each lifecycle call runs inside its own committing
 * {@link TransactionTemplate} so the {@code @Transactional} flush is faithful.
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
        EndpointAuditService.class,
        EndpointInstallPreflightService.class,
        EndpointCommandSecretService.class,
        AesGcmDeviceSecretProtector.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class EndpointDeviceDecommissionLifecyclePostgresIntegrationTest {

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
    }

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-ea78-ea78-ea78-aaaaaaaaaaaa");
    private static final String SUBJECT = "admin@example.com";

    @Autowired private EndpointDeviceLifecycleService lifecycleService;
    @Autowired private EndpointAdminCommandService commandService;
    @Autowired private EndpointDeviceRepository deviceRepository;
    @Autowired private EndpointDeviceLifecycleAuditRepository lifecycleAuditRepository;
    @Autowired private EndpointCommandRepository commandRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, SUBJECT);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommission_setsStatus_writesAudit_andCancelsPendingCommand() {
        UUID deviceId = seedEnrolledOnlineDevice();
        tx().execute(s -> commandService.createCommand(context(), deviceId,
                new CreateEndpointCommandRequest(CommandType.COLLECT_INVENTORY,
                        "inv-" + UUID.randomUUID(), "seed", null, 100, 3, null, null, null)));

        tx().executeWithoutResult(s ->
                lifecycleService.decommission(TENANT, SUBJECT, deviceId, "ops cleanup"));

        assertThat(deviceRepository.findVisibleToOrgAndId(TENANT, deviceId))
                .get().extracting(d -> d.getStatus()).isEqualTo(DeviceStatus.DECOMMISSIONED);

        assertThat(lifecycleAuditRepository.findTopByDeviceIdAndOrgIdAndActionOrderByCreatedAtDesc(
                deviceId, TENANT, DeviceLifecycleAction.DECOMMISSION))
                .get().satisfies(a -> {
                    assertThat(a.getFromStatus()).isEqualTo(DeviceStatus.ONLINE);
                    assertThat(a.getToStatus()).isEqualTo(DeviceStatus.DECOMMISSIONED);
                    assertThat(a.getReason()).isEqualTo("ops cleanup");
                    assertThat(a.getCancelledCommands()).isGreaterThanOrEqualTo(1);
                });

        assertThat(commandRepository.findByTenantIdAndDevice_IdOrderByIssuedAtDesc(TENANT, deviceId))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(CommandStatus.CANCELLED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decommission_alreadyDecommissioned_isConflict() {
        UUID deviceId = seedEnrolledOnlineDevice();
        tx().executeWithoutResult(s -> lifecycleService.decommission(TENANT, SUBJECT, deviceId, "first"));

        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                lifecycleService.decommission(TENANT, SUBJECT, deviceId, "second")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reactivate_enrolledDevice_targetsOffline_writesAudit() {
        UUID deviceId = seedEnrolledOnlineDevice();
        tx().executeWithoutResult(s -> lifecycleService.decommission(TENANT, SUBJECT, deviceId, "retire"));

        tx().executeWithoutResult(s -> lifecycleService.reactivate(TENANT, SUBJECT, deviceId, "back in service"));

        assertThat(deviceRepository.findVisibleToOrgAndId(TENANT, deviceId))
                .get().extracting(d -> d.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(lifecycleAuditRepository.findTopByDeviceIdAndOrgIdAndActionOrderByCreatedAtDesc(
                deviceId, TENANT, DeviceLifecycleAction.REACTIVATE))
                .get().satisfies(a -> {
                    assertThat(a.getFromStatus()).isEqualTo(DeviceStatus.DECOMMISSIONED);
                    assertThat(a.getToStatus()).isEqualTo(DeviceStatus.OFFLINE);
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reactivate_notDecommissioned_isConflict() {
        UUID deviceId = seedEnrolledOnlineDevice();
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                lifecycleService.reactivate(TENANT, SUBJECT, deviceId, "noop")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    // ───────────────────────── seed ─────────────────────────

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
