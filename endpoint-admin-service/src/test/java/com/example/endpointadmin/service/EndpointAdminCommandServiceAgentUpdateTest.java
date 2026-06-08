package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateAgentUpdateRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.repository.EndpointAgentUpdateReleaseRepository;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-032 — dedicated UPDATE_AGENT dispatch path.
 *
 * <p>These tests pin the trust boundary: admins choose an approved release id,
 * while URL/hash/signer/tier fields are resolved from the backend release
 * catalog and only dispatched to devices that recently advertised the
 * UPDATE_AGENT capability.
 */
@ExtendWith(MockitoExtension.class)
class EndpointAdminCommandServiceAgentUpdateTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RELEASE_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMAND_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-06-06T09:00:00Z");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "ops@example.com");

    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private EndpointCommandApprovalRepository approvalRepository;
    @Mock private EndpointDeviceRepository deviceRepository;
    @Mock private EndpointSoftwareCatalogItemRepository catalogRepository;
    @Mock private EndpointAgentUpdateReleaseRepository agentUpdateReleaseRepository;
    @Mock private EndpointHeartbeatRepository heartbeatRepository;
    @Mock private EndpointInstallPreflightService preflightService;
    @Mock private EndpointCommandSecretService commandSecretService;
    @Mock private EndpointAuditService auditService;

    private EndpointAdminCommandService service;

    @BeforeEach
    void setUp() {
        service = new EndpointAdminCommandService(
                commandRepository,
                resultRepository,
                approvalRepository,
                deviceRepository,
                catalogRepository,
                agentUpdateReleaseRepository,
                heartbeatRepository,
                preflightService,
                commandSecretService,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5),
                Set.of(CommandType.COLLECT_INVENTORY),
                event -> { });
    }

    @Test
    void createAgentUpdateQueuesCommandFromApprovedReleaseCatalog() {
        EndpointDevice device = device();
        EndpointAgentUpdateRelease release = approvedRelease(AgentUpdateSigningTier.TRUSTED_SIGNED);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(release));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat(NOW.minusSeconds(30), List.of("COLLECT_INVENTORY", "UPDATE_AGENT"))));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-update-agent:" + DEVICE_ID + ":" + RELEASE_UUID + ":client-key-1"))
                .thenReturn(Optional.empty());
        when(commandRepository.saveAndFlush(any(EndpointCommand.class))).thenAnswer(invocation -> {
            EndpointCommand command = invocation.getArgument(0);
            setField(command, "id", COMMAND_ID);
            return command;
        });
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        EndpointCommandDto dto = service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "client-key-1",
                        "pilot self-update smoke", DeploymentRing.PILOT, null, null));

        assertThat(dto.type()).isEqualTo(CommandType.UPDATE_AGENT);
        assertThat(dto.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(dto.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(dto.maxAttempts()).isEqualTo(1);
        assertThat(dto.idempotencyKey())
                .isEqualTo("admin-update-agent:" + DEVICE_ID + ":" + RELEASE_UUID + ":client-key-1");
        assertThat(dto.payload())
                .containsEntry("releaseId", "agent-0.2.0")
                .containsEntry("channel", "PILOT")
                .containsEntry("ring", "PILOT")
                .containsEntry("targetVersion", "0.2.0")
                .containsEntry("binaryUrl", "https://updates.example.com/endpoint-agent-0.2.0.exe")
                .containsEntry("claimedSha256", "a".repeat(64))
                .containsEntry("claimedSignerThumbprint", "B".repeat(40))
                .containsEntry("signingTier", "TRUSTED")
                .containsEntry("maxBytes", 25_000_000L)
                .containsEntry("requiredDeploymentRing", "PILOT")
                .containsEntry("reason", "pilot self-update smoke");
        verify(auditService).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createAgentUpdateRejectsUnapprovedRelease() {
        EndpointDevice device = device();
        EndpointAgentUpdateRelease release = approvedRelease(AgentUpdateSigningTier.TRUSTED_SIGNED);
        release.setStatus(AgentUpdateReleaseStatus.DRAFT);
        release.setEnabled(false);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(release));

        assertThatThrownBy(() -> service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "key",
                        "pilot self-update smoke", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("APPROVED and enabled");
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createAgentUpdateRejectsWhenRequiredDeploymentRingDoesNotMatchDevice() {
        EndpointDevice device = device();
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "key",
                        "pilot self-update smoke", DeploymentRing.IT, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("not assigned to the requested deployment ring");
        verify(agentUpdateReleaseRepository, never())
                .findByTenantIdAndReleaseId(any(), any());
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createAgentUpdateRejectsStaleHeartbeatBeforeQueueingCommand() {
        EndpointDevice device = device();
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(approvedRelease(AgentUpdateSigningTier.TRUSTED_SIGNED)));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat(NOW.minus(Duration.ofMinutes(10)), List.of("UPDATE_AGENT"))));

        assertThatThrownBy(() -> service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "key",
                        "pilot self-update smoke", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FAILED_DEPENDENCY)
                .hasMessageContaining("heartbeat is stale");
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createAgentUpdateRejectsHeartbeatWithoutUpdateAgentCapability() {
        EndpointDevice device = device();
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(approvedRelease(AgentUpdateSigningTier.TRUSTED_SIGNED)));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat(NOW.minusSeconds(30), List.of("COLLECT_INVENTORY"))));

        assertThatThrownBy(() -> service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "key",
                        "pilot self-update smoke", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("UPDATE_AGENT");
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createAgentUpdateReplaysExistingCommandByCanonicalIdempotencyKey() {
        EndpointDevice device = device();
        EndpointAgentUpdateRelease release = approvedRelease(AgentUpdateSigningTier.LAB_ONLY_EVIDENCE);
        EndpointCommand existing = existingUpdateCommand(device, release);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(release));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat(NOW.minusSeconds(30), Map.of("UPDATE_AGENT", true))));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-update-agent:" + DEVICE_ID + ":" + RELEASE_UUID + ":client-key-1"))
                .thenReturn(Optional.of(existing));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        EndpointCommandDto dto = service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "client-key-1",
                        "pilot self-update smoke", null, null, null));

        assertThat(dto.id()).isEqualTo(COMMAND_ID);
        assertThat(dto.payload()).containsEntry("signingTier", "LAB_ONLY_EVIDENCE");
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    @Test
    void createAgentUpdateRejectsIdempotencyReplayWithDifferentScheduleOrRing() {
        EndpointDevice device = device();
        EndpointAgentUpdateRelease release = approvedRelease(AgentUpdateSigningTier.LAB_ONLY_EVIDENCE);
        EndpointCommand existing = existingUpdateCommand(device, release);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID)).thenReturn(Optional.of(device));
        when(agentUpdateReleaseRepository.findByTenantIdAndReleaseId(TENANT_ID, "agent-0.2.0"))
                .thenReturn(Optional.of(release));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat(NOW.minusSeconds(30), Map.of("UPDATE_AGENT", true))));
        when(commandRepository.findByTenantIdAndIdempotencyKey(TENANT_ID,
                "admin-update-agent:" + DEVICE_ID + ":" + RELEASE_UUID + ":client-key-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createAgentUpdate(TENANT, DEVICE_ID,
                new CreateAgentUpdateRequest("agent-0.2.0", "client-key-1",
                        "pilot self-update smoke", DeploymentRing.PILOT,
                        NOW.plus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(1)))))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("Idempotency key is already used");
        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
    }

    private static EndpointDevice device() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        device.setDeploymentRing(DeploymentRing.PILOT);
        setField(device, "id", DEVICE_ID);
        return device;
    }

    private static EndpointAgentUpdateRelease approvedRelease(AgentUpdateSigningTier tier) {
        EndpointAgentUpdateRelease release = new EndpointAgentUpdateRelease();
        release.setTenantId(TENANT_ID);
        release.setReleaseId("agent-0.2.0");
        release.setChannel(AgentUpdateChannel.PILOT);
        release.setTargetVersion("0.2.0");
        release.setBinaryUrl("https://updates.example.com/endpoint-agent-0.2.0.exe");
        release.setSha256("a".repeat(64));
        release.setSignerThumbprint("B".repeat(40));
        release.setSigningTier(tier);
        release.setMaxBytes(25_000_000L);
        release.setStatus(AgentUpdateReleaseStatus.APPROVED);
        release.setEnabled(true);
        release.setCreatedBySubject("maker@example.com");
        release.setLastUpdatedBySubject("checker@example.com");
        release.setApprovedBySubject("checker@example.com");
        setField(release, "id", RELEASE_UUID);
        setField(release, "version", 7L);
        return release;
    }

    private static EndpointHeartbeat heartbeat(Instant receivedAt, Object capabilities) {
        EndpointHeartbeat heartbeat = new EndpointHeartbeat();
        heartbeat.setTenantId(TENANT_ID);
        heartbeat.setDevice(device());
        heartbeat.setReceivedAt(receivedAt);
        heartbeat.setPayload(Map.of("capabilities", capabilities));
        return heartbeat;
    }

    private static EndpointCommand existingUpdateCommand(EndpointDevice device,
                                                         EndpointAgentUpdateRelease release) {
        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(TENANT_ID);
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.UPDATE_AGENT);
        cmd.setStatus(CommandStatus.QUEUED);
        cmd.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        cmd.setIdempotencyKey("admin-update-agent:" + DEVICE_ID + ":" + RELEASE_UUID + ":client-key-1");
        cmd.setIssuedBySubject("ops@example.com");
        cmd.setIssuedAt(NOW);
        cmd.setVisibleAfterAt(NOW);
        cmd.setPriority(100);
        cmd.setAttemptCount(0);
        cmd.setMaxAttempts(1);
        cmd.setPayload(Map.of(
                "releaseId", release.getReleaseId(),
                "channel", release.getChannel().name(),
                "ring", "PILOT",
                "targetVersion", release.getTargetVersion(),
                "binaryUrl", release.getBinaryUrl(),
                "claimedSha256", release.getSha256(),
                "claimedSignerThumbprint", release.getSignerThumbprint(),
                "signingTier", "LAB_ONLY_EVIDENCE",
                "maxBytes", release.getMaxBytes(),
                "reason", "pilot self-update smoke"));
        setField(cmd, "id", COMMAND_ID);
        return cmd;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
