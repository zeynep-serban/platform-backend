package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.AuditIntegrityVerifier;
import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyResponse;
import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.ClearDisplayPolicyRequest;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #508 slice-2b — end-to-end PG integration proof of the Codex 019ea911 RED-fix
 * WIRING: the dispatch service writes only a revision + a PENDING command, and
 * the current desired-state row is promoted ONLY when a second admin APPROVES the
 * command via the existing dual-control surface (which publishes the generic
 * decision event the {@link DisplayPolicyApprovalListener} consumes IN the same
 * transaction). A REJECT leaves the current row unwritten.
 *
 * <p>Mirrors {@link EndpointInstallCommandAuditPostgresIntegrationTest}: real PG
 * 16 + Flyway + {@code ddl-auto=validate} + the genuine audit chain lock, each
 * scenario in its own committed {@link TransactionTemplate} transaction (the
 * class is {@code NOT_SUPPORTED} so the default rollback tx does not wrap the
 * flush, and the synchronous in-tx approval event truly fires + commits).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        TimeConfig.class,
        EndpointAdminCommandService.class,
        EndpointDisplayPolicyService.class,
        DisplayPolicyApprovalListener.class,
        EndpointAuditService.class,
        EndpointInstallPreflightService.class,
        EndpointCommandSecretService.class,
        AesGcmDeviceSecretProtector.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class DisplayPolicyDispatchPostgresIntegrationTest {

    private static final String PROPOSER = "alice@example.com";
    private static final String APPROVER = "bob@example.com";

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
        registry.add("endpoint-admin.secrets.encryption-key",
                () -> "test-endpoint-command-secret-key");
        // Enable the dark-shipped display-policy surface for the test.
        registry.add("endpoint-admin.display-policy.enabled", () -> "true");
    }

    @Autowired private EndpointDisplayPolicyService displayPolicyService;
    @Autowired private EndpointAdminCommandService commandService;
    @Autowired private EndpointDisplayPolicyRepository policyRepository;
    @Autowired private EndpointDisplayPolicyRevisionRepository revisionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void enforceThenApprove_promotesCurrent_andDispatchNeverWritesCurrent() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        AdminDisplayPolicyResponse proposed = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()));
        UUID commandId = proposed.openProposal().commandId();

        // Dispatch wrote a revision + PENDING command but NOT the current row.
        assertThat(proposed.operation()).isNull();
        assertThat(activePolicyCount(device)).isZero();
        assertThat(revisionRepository.findOpenProposals(tenant, device)).hasSize(1);

        // A DIFFERENT admin approves → the listener promotes the current row.
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), commandId,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));

        EndpointDisplayPolicy current = currentRow(tenant, device);
        assertThat(current.getOperation()).isEqualTo(DisplayPolicyOperation.ENFORCE);
        assertThat(current.getClearedAt()).isNull();
        assertThat(current.getScreensaverEnabled()).isTrue();
        assertThat(current.getWallpaperStyle().name()).isEqualTo("FILL");
        assertThat(current.getCreatedBySubject()).isEqualTo(PROPOSER);
        assertThat(current.getLastUpdatedBySubject()).isEqualTo(APPROVER);
    }

    @Test
    void enforceThenReject_leavesCurrentUnwritten() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        AdminDisplayPolicyResponse proposed = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()));
        UUID commandId = proposed.openProposal().commandId();

        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), commandId,
                new ApproveEndpointCommandRequest(ApprovalDecision.REJECT, "not for kiosk")));

        // RED-fix: a rejected proposal NEVER becomes current truth.
        assertThat(policyRepository.findByTenantIdAndDeviceId(tenant, device)).isEmpty();
    }

    @Test
    void enforceApprove_thenClearApprove_flipsCurrentToCleared() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        UUID enforceCmd = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()))
                .openProposal().commandId();
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), enforceCmd,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));
        assertThat(currentRow(tenant, device).getOperation()).isEqualTo(DisplayPolicyOperation.ENFORCE);

        UUID clearCmd = tx().execute(s ->
                displayPolicyService.clear(ctx(tenant, PROPOSER), device,
                        new ClearDisplayPolicyRequest("kiosk decommission")))
                .openProposal().commandId();
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), clearCmd,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));

        EndpointDisplayPolicy current = currentRow(tenant, device);
        assertThat(current.getOperation()).isEqualTo(DisplayPolicyOperation.CLEAR);
        assertThat(current.getClearedAt()).isNotNull();
        assertThat(current.getClearedBySubject()).isEqualTo(APPROVER);
        assertThat(current.getScreensaverEnabled()).isNull();
        // exactly one row per device (flipped in place, not a second row)
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_display_policies WHERE device_id = ?",
                Integer.class, device);
        assertThat(rows).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AdminTenantContext ctx(UUID tenant, String subject) {
        return new AdminTenantContext(tenant, subject);
    }

    private static SetDisplayPolicyRequest enforceRequest() {
        return new SetDisplayPolicyRequest(
                DisplayPolicyOperation.ENFORCE,
                "kiosk lockdown",
                new SetDisplayPolicyRequest.Screensaver(true, 600, true,
                        "c:\\windows\\system32\\scrnsave.scr"),
                new SetDisplayPolicyRequest.Wallpaper(true, "fill", true,
                        "wallpapers/corp.png", null, "image/png"));
    }

    private EndpointDisplayPolicy currentRow(UUID tenant, UUID device) {
        Optional<EndpointDisplayPolicy> current =
                policyRepository.findByTenantIdAndDeviceId(tenant, device);
        assertThat(current).isPresent();
        return current.get();
    }

    private int activePolicyCount(UUID device) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_display_policies WHERE device_id = ? AND cleared_at IS NULL",
                Integer.class, device);
        return n == null ? 0 : n;
    }

    private UUID seedDeviceWithFreshCapableHeartbeat(UUID tenant) {
        UUID device = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, last_seen_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'ONLINE', 'WINDOWS', ?, ?, ?, 0)",
                device, tenant, "host-" + device.toString().substring(0, 8), now, now, now);
        jdbc.update("INSERT INTO endpoint_heartbeats "
                        + "(id, tenant_id, device_id, received_at, payload) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), tenant, device, now,
                "{\"capabilities\":[\"SET_DISPLAY_POLICY\"]}");
        return device;
    }
}
