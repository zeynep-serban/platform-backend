package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandApproval;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-017 — dual-control approval gate for destructive endpoint commands.
 *
 * <p>The {@code admin-creatable-types} property is widened here to include a
 * destructive type ({@code LOCK_USER_LOGIN}) so the gate can be exercised end
 * to end; {@code CHANGE_LOCAL_PASSWORD} is also present to prove that
 * dedicated-path-only secret commands stay blocked even when the allowlist is
 * widened. The production default is {@code COLLECT_INVENTORY} only.
 */
@IsolatedH2DataJpaTest
@TestPropertySource(properties =
        "endpoint-admin.commands.admin-creatable-types=COLLECT_INVENTORY,LOCK_USER_LOGIN,CHANGE_LOCAL_PASSWORD")
@Import({TimeConfig.class, EndpointAdminCommandService.class, EndpointAuditService.class,
        NoOpAuditChainLock.class,
        EndpointCommandSecretService.class,
        AesGcmDeviceSecretProtector.class,
        // BE-021 — createInstall path depends on the install preflight
        // service to recompute the decision at command-creation time.
        EndpointInstallPreflightService.class})
class EndpointAdminCommandDualControlTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ISSUER = "issuer@example.com";
    private static final String APPROVER = "approver@example.com";

    @Autowired
    private EndpointAdminCommandService commandService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandApprovalRepository approvalRepository;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Test
    void nonDestructiveCommandSkipsApprovalGateAndIsClaimable() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-NDX"));

        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), inventoryRequest("inv-nd-1"));

        assertThat(created.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(created.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(claimableIds(device)).contains(created.id());
    }

    @Test
    void destructiveCommandWithoutReasonIsRejectedWith400() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-DBR"));

        assertResponseStatus(
                () -> commandService.createCommand(
                        context(ISSUER), device.getId(), destructiveRequest("lock-noreason", "  ")),
                HttpStatus.BAD_REQUEST,
                "reason is required");
    }

    @Test
    void destructiveCommandIsParkedPendingAndExcludedFromClaim() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-DPP"));

        EndpointCommandDto destructive = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-pending", "compromised account"));
        EndpointCommandDto benign = commandService.createCommand(
                context(ISSUER), device.getId(), inventoryRequest("inv-sibling"));

        assertThat(destructive.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(destructive.status()).isEqualTo(CommandStatus.QUEUED);

        List<UUID> claimable = claimableIds(device);
        assertThat(claimable).contains(benign.id());
        assertThat(claimable).doesNotContain(destructive.id());
    }

    @Test
    void selfApprovalIsRejectedWith409() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-SELF"));
        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-self", "compromised account"));

        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(ISSUER), created.id(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "self sign-off")),
                HttpStatus.CONFLICT,
                "different admin");

        assertThat(commandService.getCommand(context(ISSUER), created.id()).approvalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void secondAdminApprovalMakesDestructiveCommandClaimable() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-APV"));
        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-approve", "compromised account"));
        assertThat(claimableIds(device)).doesNotContain(created.id());

        EndpointCommandDto approved = commandService.approveCommand(
                context(APPROVER), created.id(),
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "verified with security team"));

        assertThat(approved.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(claimableIds(device)).contains(created.id());

        EndpointCommandApproval approval = approvalRepository.findByCommandId(created.id()).orElseThrow();
        assertThat(approval.getDecision()).isEqualTo(ApprovalDecision.APPROVE);
        assertThat(approval.getIssuerSubject()).isEqualTo(ISSUER);
        assertThat(approval.getDecidedBySubject()).isEqualTo(APPROVER);
    }

    @Test
    void secondAdminRejectionCancelsCommand() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-REJ"));
        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-reject", "suspicious request"));

        EndpointCommandDto rejected = commandService.approveCommand(
                context(APPROVER), created.id(),
                new ApproveEndpointCommandRequest(ApprovalDecision.REJECT, "not justified by the incident"));

        assertThat(rejected.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.status()).isEqualTo(CommandStatus.CANCELLED);
        assertThat(rejected.cancelledAt()).isNotNull();
        assertThat(claimableIds(device)).doesNotContain(created.id());

        EndpointCommandApproval approval = approvalRepository.findByCommandId(created.id()).orElseThrow();
        assertThat(approval.getDecision()).isEqualTo(ApprovalDecision.REJECT);
        assertThat(approval.getDecidedBySubject()).isEqualTo(APPROVER);
    }

    @Test
    void rejectionWithoutReasonIsRejectedWith400() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-RNR"));
        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-rejnoreason", "incident triage"));

        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(APPROVER), created.id(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.REJECT, null)),
                HttpStatus.BAD_REQUEST,
                "reason is required");

        assertThat(commandService.getCommand(context(ISSUER), created.id()).approvalStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void approvingANonPendingCommandIsRejectedWith409() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-NPC"));

        // (a) a non-destructive command is NOT_REQUIRED and never enters the gate.
        EndpointCommandDto benign = commandService.createCommand(
                context(ISSUER), device.getId(), inventoryRequest("inv-np"));
        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(APPROVER), benign.id(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "n/a")),
                HttpStatus.CONFLICT,
                "not pending");

        // (b) an already-approved command cannot be approved a second time.
        EndpointCommandDto destructive = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-double", "compromised account"));
        commandService.approveCommand(context(APPROVER), destructive.id(),
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "verified"));
        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(APPROVER), destructive.id(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "again")),
                HttpStatus.CONFLICT,
                "not pending");
    }

    @Test
    void approvingAnExpiredCommandIsRejectedWith409() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-EXP"));
        // Built directly: createCommand rejects an already-elapsed expiry.
        EndpointCommand expired = new EndpointCommand();
        expired.setTenantId(TENANT_ID);
        expired.setDevice(device);
        expired.setCommandType(CommandType.LOCK_USER_LOGIN);
        expired.setIdempotencyKey("lock-expired");
        expired.setStatus(CommandStatus.QUEUED);
        expired.setApprovalStatus(ApprovalStatus.PENDING);
        expired.setPayload(Map.of("reason", "stale incident"));
        expired.setPriority(100);
        expired.setAttemptCount(0);
        expired.setMaxAttempts(3);
        expired.setIssuedBySubject(ISSUER);
        expired.setVisibleAfterAt(Instant.now().minusSeconds(7200));
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        expired.setIssuedAt(Instant.now().minusSeconds(7200));
        EndpointCommand saved = commandRepository.saveAndFlush(expired);

        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(APPROVER), saved.getId(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "too late")),
                HttpStatus.CONFLICT,
                "expired");

        EndpointCommand reloaded = commandRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void approvingACancelledCommandIsRejectedWith409AndWritesNoAudit() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-CANX"));
        // BE-034 — the device-decommission cascade CANCELs a queued command
        // while its dual-control approvalStatus is still PENDING. Built directly
        // because createCommand never lands a command in a terminal status.
        EndpointCommand cancelled = new EndpointCommand();
        cancelled.setTenantId(TENANT_ID);
        cancelled.setDevice(device);
        cancelled.setCommandType(CommandType.LOCK_USER_LOGIN);
        cancelled.setIdempotencyKey("lock-cancelled");
        cancelled.setStatus(CommandStatus.CANCELLED);
        cancelled.setApprovalStatus(ApprovalStatus.PENDING);
        cancelled.setPayload(Map.of("reason", "device decommissioned"));
        cancelled.setPriority(100);
        cancelled.setAttemptCount(0);
        cancelled.setMaxAttempts(3);
        cancelled.setIssuedBySubject(ISSUER);
        cancelled.setVisibleAfterAt(Instant.now());
        cancelled.setCancelledAt(Instant.now());
        cancelled.setIssuedAt(Instant.now().minusSeconds(60));
        EndpointCommand saved = commandRepository.saveAndFlush(cancelled);

        assertResponseStatus(
                () -> commandService.approveCommand(
                        context(APPROVER), saved.getId(),
                        new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "stale approve")),
                HttpStatus.CONFLICT,
                "no longer actionable");

        // The decision is rejected before any state mutation: the command stays
        // CANCELLED + PENDING, no approval row is written, and crucially no
        // ENDPOINT_COMMAND_APPROVED audit row is emitted.
        EndpointCommand reloaded = commandRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CommandStatus.CANCELLED);
        assertThat(reloaded.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalRepository.findByCommandId(saved.getId())).isEmpty();
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .doesNotContain("ENDPOINT_COMMAND_APPROVED");
    }

    @Test
    void auditTrailRecordsCreateAndApprovalEvents() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-AUD"));
        EndpointCommandDto created = commandService.createCommand(
                context(ISSUER), device.getId(), destructiveRequest("lock-audit", "compromised account"));
        commandService.approveCommand(context(APPROVER), created.id(),
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, "verified with security team"));

        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("ENDPOINT_COMMAND_CREATED", "ENDPOINT_COMMAND_APPROVED");
    }

    @Test
    void destructiveReasonOverridesConflictingPayloadReason() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-CANON"));
        // The agent reads its reason from payload.reason; the dedicated `reason`
        // field is validated/mandatory, so it must win over a conflicting
        // caller-supplied payload.reason (Codex 019e50e0 P1).
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.LOCK_USER_LOGIN, "lock-canon", "authoritative incident reason",
                Map.of("reason", "caller-supplied stale reason", "targetUser", "corp.local\\jdoe"),
                null, null, null, null, null);

        EndpointCommandDto created = commandService.createCommand(context(ISSUER), device.getId(), request);

        assertThat(created.payload()).containsEntry("reason", "authoritative incident reason");
        assertThat(created.payload()).containsEntry("targetUser", "corp.local\\jdoe");
    }

    @Test
    void localPasswordChangeRejectsGenericPathEvenWhenAdminCreatableAllowlistIsWidened() {
        EndpointDevice device = deviceRepository.saveAndFlush(device("PC-PWD"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.CHANGE_LOCAL_PASSWORD,
                "pwd-generic-block",
                "local recovery request",
                Map.of("username", "ea-recovery", "newPassword", "MustNotPersist#123"),
                null,
                null,
                null,
                null,
                null);

        assertResponseStatus(
                () -> commandService.createCommand(context(ISSUER), device.getId(), request),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "dedicated local recovery");
        assertThat(commandRepository.findAll()).isEmpty();
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID)).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private void assertResponseStatus(ThrowingCallable callable,
                                      HttpStatus expectedStatus,
                                      String messageFragment) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expectedStatus))
                .hasMessageContaining(messageFragment);
    }

    private List<UUID> claimableIds(EndpointDevice device) {
        return commandRepository.findClaimCandidatesForDevice(
                        device.getId(),
                        DeviceStatus.ONLINE,
                        CommandStatus.QUEUED,
                        CommandStatus.DELIVERED,
                        Instant.now().plusSeconds(60),
                        PageRequest.of(0, 50))
                .stream()
                .map(EndpointCommand::getId)
                .toList();
    }

    private AdminTenantContext context(String subject) {
        return new AdminTenantContext(TENANT_ID, subject);
    }

    private CreateEndpointCommandRequest inventoryRequest(String idempotencyKey) {
        return new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY, idempotencyKey, "inventory refresh",
                Map.of("requestedDetail", "basic"), null, null, null, null, null);
    }

    private CreateEndpointCommandRequest destructiveRequest(String idempotencyKey, String reason) {
        return new CreateEndpointCommandRequest(
                CommandType.LOCK_USER_LOGIN, idempotencyKey, reason,
                Map.of("targetUser", "corp.local\\jdoe"), null, null, null, null, null);
    }

    private EndpointDevice device(String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.2.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(Instant.now());
        return device;
    }
}
