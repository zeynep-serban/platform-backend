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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
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
 * BE-021 — Mockito-only regression guard for the INSTALL_SOFTWARE branch
 * of {@link EndpointAgentCommandService#submitResult}. Codex 019e6dfb
 * iter-6 closing note follow-up:
 *
 * <ul>
 *   <li>Raw forbidden details → 400 + no raw payload persistence (no result
 *       row, no audit row), while the command is terminal FAILED with a
 *       bounded operator-facing rejection reason.</li>
 *   <li>Clean details → the policy's redacted map reference is the SAME
 *       object that lands in
 *       {@code endpoint_command_results.result_payload.details} and that
 *       reaches
 *       {@link EndpointInstallAuditService#recordInstallResult}. Proves
 *       the double-redact contract: raw payload never leaks past the
 *       policy step.</li>
 *   <li>Every {@link CommandResultStatus} value (all terminal per
 *       {@code isTerminalResult}) → audit row is written exactly once.
 *       The enum has no non-terminal value today; this guard catches a
 *       future enum widening that would silently skip the audit
 *       write.</li>
 * </ul>
 *
 * The {@code InstallEvidencePayloadPolicy} dependency is the real
 * component (no mocking) so the test exercises the actual validate +
 * redact pipeline.
 */
@ExtendWith(MockitoExtension.class)
class EndpointAgentCommandServiceInstallBranchTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CATALOG_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");
    private static final Instant PREFLIGHT_AT = Instant.parse("2026-05-28T11:59:00Z");
    private static final String CLAIM_ID = "claim-install-001";

    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private SoftwareInventoryPayloadPolicy inventoryPayloadPolicy;
    @Mock private EndpointSoftwareInventoryService softwareInventoryService;
    @Mock private EndpointInstallAuditService installAuditService;
    // BE-022 — hardware inventory dependencies added to
    // EndpointAgentCommandService constructor (Faz 22.5, Codex
    // 019e7007 iter-4 absorb).
    @Mock private com.example.endpointadmin.security.HardwareInventoryPayloadPolicy hardwareInventoryPayloadPolicy;
    @Mock private EndpointHardwareInventoryService hardwareInventoryService;
    // BE device-health dependencies (Faz 22.5, AG-033 ingest) added to
    // the EndpointAgentCommandService constructor.
    @Mock private com.example.endpointadmin.security.DeviceHealthPayloadPolicy deviceHealthPayloadPolicy;
    @Mock private EndpointDeviceHealthService deviceHealthService;
    // AG-036 outdated-software dependencies (Faz 22.5) added to the
    // EndpointAgentCommandService constructor.
    @Mock private com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy outdatedSoftwarePayloadPolicy;
    @Mock private EndpointOutdatedSoftwareService outdatedSoftwareService;
    // AG-037 hotfix-posture dependencies (Faz 22.5) added to the
    // EndpointAgentCommandService constructor.
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
    @Mock private EndpointUninstallAuditService uninstallAuditService;
    @Mock private EndpointCommandSecretService commandSecretService;

    private EndpointAgentCommandService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        InstallEvidencePayloadPolicy installPolicy = new InstallEvidencePayloadPolicy(
                InstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
                InstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);
        com.example.endpointadmin.security.UninstallEvidencePayloadPolicy uninstallPolicy =
                new com.example.endpointadmin.security.UninstallEvidencePayloadPolicy(
                        com.example.endpointadmin.security.UninstallEvidencePayloadPolicy
                                .MAX_REDACTED_BYTES_DEFAULT,
                        com.example.endpointadmin.security.UninstallEvidencePayloadPolicy
                                .MAX_SUMMARY_BYTES_DEFAULT);
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
                commandSecretService,
                fixed,
                300L);
    }

    @Test
    void submitResultRejectsForbiddenKeyAndSkipsAllPersistence() {
        EndpointCommand command = installCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("stage", "post_install");
        forbidden.put("exitCode", 0);
        // Value is a non-secret placeholder; the policy rejects on the
        // forbidden KEY (licenseKey), so the value's shape is irrelevant
        // to coverage and we keep it clearly non-secret-shaped to avoid
        // gitleaks generic-api-key false positives. Mirrors the
        // placeholder used in InstallEvidencePayloadPolicyTest.
        forbidden.put("licenseKey", "test-fake-license-marker-no-real-secret");

        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.SUCCEEDED, forbidden);

        assertThatThrownBy(() -> service.submitResult(principal(), COMMAND_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("licenseKey");

        verify(commandRepository, times(1)).saveAndFlush(command);
        verify(commandSecretService, times(1)).clearIfTerminal(command);
        assertThat(command.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(command.getStartedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(command.getCompletedAt()).isEqualTo(NOW);
        assertThat(command.getLockedBy()).isNull();
        assertThat(command.getLockedUntil()).isNull();
        assertThat(command.getLastError())
                .startsWith("RESULT_REJECTED:")
                .contains("licenseKey")
                .hasSizeLessThanOrEqualTo(512);
        verify(resultRepository, never()).saveAndFlush(any(EndpointCommandResult.class));
        verify(installAuditService, never()).recordInstallResult(
                any(), any(), any(), any(), any());
    }

    @Test
    void submitResultRejectsRawJwtValueAndSkipsAllPersistence() {
        EndpointCommand command = installCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        Map<String, Object> withJwt = new LinkedHashMap<>();
        withJwt.put("stage", "post_install");
        withJwt.put("exitCode", 0);
        // Synthetic JWT fixture — header/payload/signature segments are
        // assembled at runtime so the literal does not appear in source
        // and gitleaks does not flag it. Same pattern as
        // InstallEvidencePayloadPolicyTest.
        String jwtFixture = "eyJ" + "headerPlaceholder.eyJ"
                + "payloadPlaceholder.sig" + "Placeholder";
        withJwt.put("errorMessage", "auth header was " + jwtFixture);

        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.FAILED, withJwt);

        assertThatThrownBy(() -> service.submitResult(principal(), COMMAND_ID, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("JWT");

        verify(commandRepository, times(1)).saveAndFlush(command);
        verify(commandSecretService, times(1)).clearIfTerminal(command);
        assertThat(command.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(command.getStartedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(command.getCompletedAt()).isEqualTo(NOW);
        assertThat(command.getLockedBy()).isNull();
        assertThat(command.getLockedUntil()).isNull();
        assertThat(command.getLastError())
                .startsWith("RESULT_REJECTED:")
                .contains("JWT")
                .doesNotContain(jwtFixture)
                .hasSizeLessThanOrEqualTo(512);
        verify(resultRepository, never()).saveAndFlush(any(EndpointCommandResult.class));
        verify(installAuditService, never()).recordInstallResult(
                any(), any(), any(), any(), any());
    }

    @Test
    void submitResultRedactsPayloadAndWritesSameMapToResultRowAndAuditRow() {
        EndpointCommand command = installCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        // Validate-passing payload that REQUIRES policy.redact() to
        // transform it. A future refactor that quietly drops the
        // redact() call (e.g. degrades to `new LinkedHashMap<>(request
        // .details())`) would still satisfy the isSameAs / isNotSameAs
        // identity contract below — but it would NOT redact the
        // Windows user path nor drop the off-allowlist processList key,
        // and those assertions would catch the leak.
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("stage", "post_install");
        clean.put("exitCode", 1);
        // Triggers USERS_PATH redaction → REDACTED_LITERAL in place of
        // C:\Users\Bob\... segment.
        clean.put("stdoutSummary", "installed under C:\\Users\\Bob\\AppData\\Local");
        // Off-allowlist top-level key — passes validate (not forbidden),
        // dropped by redact (not in REDACT_ALLOWLIST_KEYS).
        clean.put("processList", List.of("System", "AcmeApp.exe"));
        Map<String, Object> detection = new LinkedHashMap<>();
        detection.put("packageId", "7zip.7zip");
        detection.put("version", "24.07");
        clean.put("detection", detection);
        Map<String, Object> postVerification = new LinkedHashMap<>();
        postVerification.put("status", "SATISFIED");
        clean.put("postVerification", postVerification);

        // FAILED status (not SUCCEEDED) — guards against a refactor that
        // would only redact the success branch and silently leak raw
        // details on failure rows.
        AgentCommandResultRequest request = resultRequest(
                CommandResultStatus.FAILED, clean);

        service.submitResult(principal(), COMMAND_ID, request);

        ArgumentCaptor<EndpointCommandResult> resultCaptor =
                ArgumentCaptor.forClass(EndpointCommandResult.class);
        verify(resultRepository, times(1)).saveAndFlush(resultCaptor.capture());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> mapCaptor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(installAuditService, times(1)).recordInstallResult(
                any(EndpointCommand.class),
                any(EndpointCommandResult.class),
                any(AgentCommandResultRequest.class),
                mapCaptor.capture(),
                any(Instant.class));

        Object persistedDetails = resultCaptor.getValue().getResultPayload().get("details");
        Map<String, Object> auditDetails = mapCaptor.getValue();

        // Identity contract: the SAME redacted map reference is handed
        // to both the result row and the audit row. The raw request map
        // is never reused past the policy step.
        assertThat(persistedDetails)
                .as("result row details and audit row details must be the same redacted map reference")
                .isSameAs(auditDetails)
                .isNotSameAs(clean);

        @SuppressWarnings("unchecked")
        Map<String, Object> effective = (Map<String, Object>) persistedDetails;

        // Transformation evidence — proves the service actually invoked
        // policy.redact() rather than degrading to a plain map copy.
        assertThat(effective)
                .as("processList is off the redact allowlist; must be dropped")
                .doesNotContainKey("processList");
        assertThat((String) effective.get("stdoutSummary"))
                .as("Windows user path must be replaced with the policy literal")
                .doesNotContain("C:\\Users\\Bob")
                .contains(InstallEvidencePayloadPolicy.REDACTED_LITERAL);
        assertThat(effective)
                .containsEntry("stage", "post_install")
                .containsEntry("exitCode", 1)
                .containsKeys("detection", "postVerification");
    }

    @ParameterizedTest
    @EnumSource(CommandResultStatus.class)
    void submitResultWritesAuditForEveryTerminalStatus(CommandResultStatus status) {
        EndpointCommand command = installCommand();
        when(commandRepository.findByIdAndDeviceIdForUpdate(COMMAND_ID, DEVICE_ID))
                .thenReturn(Optional.of(command));
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("stage", "post_install");
        clean.put("exitCode", status == CommandResultStatus.SUCCEEDED ? 0 : 1);

        AgentCommandResultRequest request = new AgentCommandResultRequest(
                CLAIM_ID,
                1,
                status,
                status == CommandResultStatus.SUCCEEDED ? "ok" : status.name().toLowerCase(),
                clean,
                status == CommandResultStatus.SUCCEEDED ? null : "ERR-" + status.name(),
                status == CommandResultStatus.SUCCEEDED ? null : "agent reported " + status.name(),
                status == CommandResultStatus.SUCCEEDED ? 0 : 1,
                NOW.minusSeconds(30),
                NOW);

        service.submitResult(principal(), COMMAND_ID, request);

        verify(installAuditService, times(1)).recordInstallResult(
                any(EndpointCommand.class),
                any(EndpointCommandResult.class),
                any(AgentCommandResultRequest.class),
                any(),
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
                status == CommandResultStatus.SUCCEEDED ? "ok" : "failed",
                details,
                null,
                null,
                status == CommandResultStatus.SUCCEEDED ? 0 : 1,
                NOW.minusSeconds(30),
                NOW);
    }

    private static EndpointCommand installCommand() {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(TENANT_ID);
        command.setCommandType(CommandType.INSTALL_SOFTWARE);
        command.setStatus(CommandStatus.DELIVERED);
        command.setIdempotencyKey("admin-install:test:" + UUID.randomUUID());
        command.setIssuedBySubject("alice@example.com");
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
        payload.put("catalogItemId", "7zip-stable");
        payload.put("catalogItemUuid", CATALOG_UUID.toString());
        payload.put("catalogPackageId", "7zip.7zip");
        payload.put("catalogRowVersion", 3L);
        payload.put("preflightDecision", "PASS");
        payload.put("preflightDecisionAt", PREFLIGHT_AT.toString());
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
