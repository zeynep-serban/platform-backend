package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.InstallEvidencePayloadPolicy;
import com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy;
import com.example.endpointadmin.security.UninstallEvidencePayloadPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-028 Phase 2B — Mockito-only regression guard for the UNINSTALL_SOFTWARE
 * branch of {@link EndpointAgentCommandService#submitResult}.
 *
 * <ul>
 *   <li>Forbidden details → 400 + zero persistence (no result row, no audit
 *       row, no command save). Parity with the install branch fail-closed
 *       contract.</li>
 *   <li>Clean details → the policy's redacted map is the SAME object that
 *       lands in the result row AND is forwarded to
 *       {@link EndpointUninstallAuditService#recordUninstallResult}.</li>
 *   <li>Non-terminal result wire (would only fire for a future enum
 *       widening) → audit recorder NOT called.</li>
 * </ul>
 *
 * <p>The {@link UninstallEvidencePayloadPolicy} dependency is the REAL
 * component so the test exercises the actual validate + redact pipeline.
 */
@ExtendWith(MockitoExtension.class)
class EndpointAgentCommandServiceUninstallBranchTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CATALOG_UUID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-06-04T12:00:00Z");
    private static final String CLAIM_ID = "claim-uninstall-001";

    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private SoftwareInventoryPayloadPolicy inventoryPayloadPolicy;
    @Mock private EndpointSoftwareInventoryService softwareInventoryService;
    @Mock private EndpointInstallAuditService installAuditService;
    @Mock private EndpointUninstallAuditService uninstallAuditService;
    @Mock private com.example.endpointadmin.security.HardwareInventoryPayloadPolicy hardwareInventoryPayloadPolicy;
    @Mock private EndpointHardwareInventoryService hardwareInventoryService;
    @Mock private com.example.endpointadmin.security.DeviceHealthPayloadPolicy deviceHealthPayloadPolicy;
    @Mock private EndpointDeviceHealthService deviceHealthService;
    @Mock private com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy outdatedSoftwarePayloadPolicy;
    @Mock private EndpointOutdatedSoftwareService outdatedSoftwareService;
    @Mock private com.example.endpointadmin.security.HotfixPosturePayloadPolicy hotfixPosturePayloadPolicy;
    @Mock private com.example.endpointadmin.security.DiagnosticsPayloadPolicy diagnosticsPayloadPolicy;
    @Mock private com.example.endpointadmin.security.ServicesPayloadPolicy servicesPayloadPolicy;
    @Mock private com.example.endpointadmin.security.StartupExposurePayloadPolicy startupExposurePayloadPolicy;
    @Mock private com.example.endpointadmin.security.AppControlPayloadPolicy appControlPayloadPolicy;
    @Mock private EndpointHotfixPostureService hotfixPostureService;
    @Mock private EndpointDiagnosticsService diagnosticsService;
    @Mock private EndpointServicesService servicesService;
    @Mock private EndpointStartupExposureService startupExposureService;
    @Mock private EndpointAppControlService appControlService;

    private EndpointAgentCommandService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        InstallEvidencePayloadPolicy installPolicy = new InstallEvidencePayloadPolicy(
                InstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
                InstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);
        UninstallEvidencePayloadPolicy uninstallPolicy = new UninstallEvidencePayloadPolicy(
                UninstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
                UninstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);
        service = new EndpointAgentCommandService(
                commandRepository,
                resultRepository,
                inventoryPayloadPolicy,
                installPolicy,
                hardwareInventoryPayloadPolicy,
                deviceHealthPayloadPolicy,
                outdatedSoftwarePayloadPolicy,
                hotfixPosturePayloadPolicy,
                diagnosticsPayloadPolicy,
                servicesPayloadPolicy,
                startupExposurePayloadPolicy,
                appControlPayloadPolicy,
                softwareInventoryService,
                hardwareInventoryService,
                deviceHealthService,
                outdatedSoftwareService,
                hotfixPostureService,
                diagnosticsService,
                servicesService,
                startupExposureService,
                appControlService,
                installAuditService,
                uninstallPolicy,
                uninstallAuditService,
                fixed,
                300L);
    }

    @Test
    void submitResultUninstallRejectsForbiddenKeyAndSkipsAllPersistence() {
        EndpointCommand command = uninstallCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("stage", "post_uninstall");
        forbidden.put("exitCode", 0);
        // forbidden KEY — fail-closed (mirror install fail-closed contract).
        forbidden.put("licenseKey", "test-fake-license-marker-no-real-secret");

        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.SUCCEEDED, forbidden);

        assertThatThrownBy(() -> service.submitResult(principal(), COMMAND_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("licenseKey");

        verify(commandRepository, never()).saveAndFlush(any(EndpointCommand.class));
        verify(resultRepository, never()).saveAndFlush(any(EndpointCommandResult.class));
        verify(uninstallAuditService, never()).recordUninstallResult(
                any(), any(), any(), any(), any());
    }

    @Test
    void submitResultUninstallRedactsPayloadAndForwardsSameMapToAuditService() {
        EndpointCommand command = uninstallCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        // Validate-passing payload that EXERCISES policy.redact() — a
        // Windows user path triggers USERS_PATH masking, and an off-allowlist
        // top-level key (exotic_key) is dropped by the allowlist filter.
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("stage", "post_uninstall");
        clean.put("exitCode", 0);
        clean.put("stdoutSummary",
                "uninstalled under C:\\Users\\Bob\\AppData\\Local");
        clean.put("exotic_key", "off-allowlist-drop-me");
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("authority", "AUTHORITATIVE");
        clean.put("uninstall", uninstall);

        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.SUCCEEDED, clean);

        service.submitResult(principal(), COMMAND_ID, request);

        ArgumentCaptor<EndpointCommandResult> resultCaptor =
                ArgumentCaptor.forClass(EndpointCommandResult.class);
        verify(resultRepository, times(1)).saveAndFlush(resultCaptor.capture());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> mapCaptor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(uninstallAuditService, times(1)).recordUninstallResult(
                any(EndpointCommand.class),
                any(EndpointCommandResult.class),
                any(AgentCommandResultRequest.class),
                mapCaptor.capture(),
                any(Instant.class));

        Object persistedDetails = resultCaptor.getValue().getResultPayload().get("details");
        Map<String, Object> auditDetails = mapCaptor.getValue();
        // Identity contract: SAME redacted map reference reaches both
        // result row and audit recorder.
        assertThat(persistedDetails).isSameAs(auditDetails);
        // Off-allowlist key dropped by allowlist filter.
        assertThat(auditDetails).doesNotContainKey("exotic_key");
        // USERS_PATH masked into REDACTED_LITERAL.
        assertThat(auditDetails).containsEntry("stage", "post_uninstall");
        assertThat((String) auditDetails.get("stdoutSummary"))
                .doesNotContain("Bob");
        // Phase 2B: KNOWN_AUTHORITIES extension preserves the "AUTHORITATIVE"
        // hint after the policy projection.
        @SuppressWarnings("unchecked")
        Map<String, Object> auditUninstall = (Map<String, Object>) auditDetails.get("uninstall");
        assertThat(auditUninstall).containsEntry("authority", "AUTHORITATIVE");
    }

    @Test
    void submitResultUninstallWithoutDetailsStillInvokesAuditRecorder() {
        // Agent may ship a terminal result with no details (e.g. early
        // FAILED_UNSUPPORTED_PLATFORM stub). The audit row MUST still land
        // so the request reaches TERMINAL state.
        EndpointCommand command = uninstallCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.FAILED, /*details*/ null);

        service.submitResult(principal(), COMMAND_ID, request);

        verify(uninstallAuditService, times(1)).recordUninstallResult(
                any(EndpointCommand.class),
                any(EndpointCommandResult.class),
                any(AgentCommandResultRequest.class),
                /*redactedDetails*/ any(),
                any(Instant.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static DeviceCredentialResult principal() {
        return new DeviceCredentialResult(DEVICE_ID.toString(), UUID.randomUUID().toString(), NOW);
    }

    private static AgentCommandResultRequest resultRequest(CommandResultStatus status,
                                                           Map<String, Object> details) {
        return new AgentCommandResultRequest(
                CLAIM_ID,
                1,
                status,
                status == CommandResultStatus.SUCCEEDED ? "uninstall ok" : "uninstall failed",
                details,
                null,
                null,
                status == CommandResultStatus.SUCCEEDED ? 0 : 1,
                NOW.minusSeconds(30),
                NOW);
    }

    private static EndpointCommand uninstallCommand() {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(TENANT_ID);
        command.setCommandType(CommandType.UNINSTALL_SOFTWARE);
        command.setStatus(CommandStatus.DELIVERED);
        command.setIdempotencyKey("admin-uninstall-cmd:" + REQUEST_ID);
        command.setIssuedBySubject("bob@example.com");
        command.setLockedBy(CLAIM_ID);
        command.setLockedUntil(NOW.plusSeconds(300));
        command.setAttemptCount(1);
        command.setMaxAttempts(3);
        command.setPriority(100);
        command.setIssuedAt(NOW.minusSeconds(120));
        command.setVisibleAfterAt(NOW.minusSeconds(60));

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        setField(device, "id", DEVICE_ID);
        command.setDevice(device);
        setField(command, "id", COMMAND_ID);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "UNINSTALL");
        payload.put("requestId", REQUEST_ID.toString());
        payload.put("argsPolicyPreset", "UNINSTALL_DEFAULT");
        payload.put("catalogItemId", "7zip-stable");
        payload.put("catalogItemUuid", CATALOG_UUID.toString());
        payload.put("catalogPackageId", "7zip.7zip");
        payload.put("catalogRowVersion", 3L);
        command.setPayload(payload);
        return command;
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
