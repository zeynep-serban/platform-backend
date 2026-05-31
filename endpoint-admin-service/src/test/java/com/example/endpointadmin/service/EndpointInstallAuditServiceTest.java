package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.service.compliance.EndpointInstallAuditRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-021 — unit tests for {@link EndpointInstallAuditService} (Codex
 * 019e6dfb iter-4 P1-3 + P1-4). Coverage:
 *
 * <ul>
 *   <li>preflight snapshot missing → {@link IllegalStateException}
 *       (programmatic bug surfaces, parent transaction rolls back)</li>
 *   <li>terminal install success → audit row + BE-016 hash-chain event
 *       + {@link EndpointInstallAuditRecordedEvent} published</li>
 *   <li>null details → safe defaults (empty redacted_payload,
 *       postVerification=UNKNOWN, null detection fields)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EndpointInstallAuditServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CATALOG_UUID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");
    private static final Instant PREFLIGHT_AT = Instant.parse("2026-05-28T11:59:00Z");

    @Mock
    private EndpointInstallAuditRepository installAuditRepository;
    @Mock
    private EndpointAuditService auditService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EndpointInstallAuditService service;

    @BeforeEach
    void setUp() {
        service = new EndpointInstallAuditService(
                installAuditRepository, auditService, eventPublisher);
    }

    @Test
    void recordInstallResultRejectsMissingPreflightDecision() {
        EndpointCommand command = installCommand(/*decision*/ null, PREFLIGHT_AT.toString());

        assertThatThrownBy(() -> service.recordInstallResult(
                command, dummyResult(), submitRequest(), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preflightDecision");
    }

    @Test
    void recordInstallResultRejectsMissingPreflightDecisionAt() {
        EndpointCommand command = installCommand("PASS", /*at*/ null);

        assertThatThrownBy(() -> service.recordInstallResult(
                command, dummyResult(), submitRequest(), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preflightDecisionAt");
    }

    @Test
    void recordInstallResultRejectsInvalidPreflightDecision() {
        EndpointCommand command = installCommand("BLOCK", PREFLIGHT_AT.toString());

        assertThatThrownBy(() -> service.recordInstallResult(
                command, dummyResult(), submitRequest(), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preflightDecision");
    }

    @Test
    void recordInstallResultPersistsAuditAndPublishesEvent() {
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        EndpointCommandResult result = dummyResult();
        AgentCommandResultRequest request = submitRequest();
        Map<String, Object> redacted = redactedDetailsWithDetection();
        when(installAuditRepository.save(any(EndpointInstallAudit.class)))
                .thenAnswer(inv -> {
                    EndpointInstallAudit row = inv.getArgument(0);
                    try {
                        var idField = EndpointInstallAudit.class.getDeclaredField("id");
                        idField.setAccessible(true);
                        idField.set(row, UUID.randomUUID());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return row;
                });

        EndpointInstallAudit saved = service.recordInstallResult(
                command, result, request, redacted, NOW);

        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(saved.getCommandId()).isEqualTo(COMMAND_ID);
        assertThat(saved.getCatalogItemId()).isEqualTo(CATALOG_UUID);
        assertThat(saved.getCatalogPackageId()).isEqualTo("7zip.7zip");
        assertThat(saved.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(saved.getDetectedPackageId()).isEqualTo("7zip.7zip");
        assertThat(saved.getDetectedVersion()).isEqualTo("24.07");
        assertThat(saved.getRedactedPayload()).isEqualTo(redacted);

        verify(auditService, times(1)).record(
                eq(TENANT_ID), any(), eq(command),
                eq("ENDPOINT_INSTALL_RESULT_RECORDED"),
                eq("RECORD_INSTALL_RESULT"),
                anyString(), anyString(), any(), any(), any());

        ArgumentCaptor<EndpointInstallAuditRecordedEvent> eventCaptor =
                ArgumentCaptor.forClass(EndpointInstallAuditRecordedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        EndpointInstallAuditRecordedEvent event = eventCaptor.getValue();
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        assertThat(event.deviceId()).isEqualTo(DEVICE_ID);
    }

    @Test
    void recordInstallResultNullDetailsKeepsAuditWritable() {
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        when(installAuditRepository.save(any(EndpointInstallAudit.class)))
                .thenAnswer(inv -> {
                    EndpointInstallAudit row = inv.getArgument(0);
                    try {
                        var idField = EndpointInstallAudit.class.getDeclaredField("id");
                        idField.setAccessible(true);
                        idField.set(row, UUID.randomUUID());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return row;
                });

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(), null, NOW);

        assertThat(saved.getRedactedPayload()).isEmpty();
        assertThat(saved.getPostVerification().name()).isEqualTo("UNKNOWN");
        assertThat(saved.getDetectedPackageId()).isNull();
        assertThat(saved.getDetectedVersion()).isNull();
    }

    @Test
    void recordInstallResultMapsAuthoritativeNotSatisfiedToUnsatisfied() {
        // The agent's AUTHORITATIVE denial vocabulary (NOT_SATISFIED — e.g. a
        // reliable REGISTRY_UNINSTALL miss) must record as UNSATISFIED, not be
        // flattened to UNKNOWN, which would hide the authoritative signal in the
        // audit trail (Codex 019e7dce).
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                redactedDetailsWithStatus("NOT_SATISFIED"), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNSATISFIED");
    }

    @Test
    void recordInstallResultMapsInconclusiveToUnknown() {
        // CONFIRM_ONLY INCONCLUSIVE (e.g. winget list under Session-0) maps to
        // UNKNOWN — neither confirm nor deny.
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                redactedDetailsWithStatus("INCONCLUSIVE"), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordInstallResultSatisfiedTrueIsSatisfied() {
        // CANONICAL live wire shape (install_winget.go PostVerificationResult):
        // only the boolean `satisfied` is shipped. true → SATISFIED.
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                installVerdictDetails("SUCCEEDED", true), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("SATISFIED");
    }

    @Test
    void recordInstallResultSatisfiedFalseWithFailedVerificationIsUnsatisfied() {
        // BE-028 (Codex 019e7f93 #1): satisfied=false is an AUTHORITATIVE denial
        // ONLY when post-verify actually ran and failed — finalStatus=FAILED_VERIFICATION.
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                installVerdictDetails("FAILED_VERIFICATION", false), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNSATISFIED");
    }

    @Test
    void recordInstallResultSatisfiedFalseZeroValueOnInstallFailureIsUnknown() {
        // BE-028 (Codex 019e7f93 #1): the agent's `satisfied` bool is NOT
        // omitempty, so an early FAILED_INSTALL (post-verify never ran) still
        // ships satisfied=false. That must be UNKNOWN, NOT a fabricated denial.
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                installVerdictDetails("FAILED_INSTALL", false), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordInstallResultSatisfiedFalseWithoutFinalStatusIsUnknown() {
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                installVerdictDetails(null, false), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordInstallResultMalformedSatisfiedIsUnknown() {
        // BE-028 (Codex 019e7f93 #1): a malformed `satisfied` must NOT silently
        // collapse to false→UNSATISFIED via Boolean.parseBoolean; it is UNKNOWN.
        EndpointCommand command = installCommand("PASS", PREFLIGHT_AT.toString());
        stubSaveAssigningId();

        EndpointInstallAudit saved = service.recordInstallResult(
                command, dummyResult(), submitRequest(),
                installVerdictDetails("SUCCEEDED", "garbage"), NOW);

        assertThat(saved.getPostVerification().name()).isEqualTo("UNKNOWN");
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private void stubSaveAssigningId() {
        when(installAuditRepository.save(any(EndpointInstallAudit.class)))
                .thenAnswer(inv -> {
                    EndpointInstallAudit row = inv.getArgument(0);
                    try {
                        var idField = EndpointInstallAudit.class.getDeclaredField("id");
                        idField.setAccessible(true);
                        idField.set(row, UUID.randomUUID());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    return row;
                });
    }

    private static Map<String, Object> redactedDetailsWithStatus(String status) {
        // BE-028: FORWARD-COMPAT shape — a future agent may emit a tri-state
        // `status` under details.install.postVerification. The CURRENT agent
        // contract (install_winget.go) does NOT ship `status`; the live verdict
        // resolves through `satisfied` + `finalStatus` (installVerdictDetails).
        Map<String, Object> redacted = new LinkedHashMap<>();
        Map<String, Object> install = new LinkedHashMap<>();
        Map<String, Object> postVerification = new LinkedHashMap<>();
        postVerification.put("status", status);
        install.put("postVerification", postVerification);
        redacted.put("install", install);
        return redacted;
    }

    private static Map<String, Object> installVerdictDetails(String finalStatus, Object satisfied) {
        // BE-028: CANONICAL live wire shape — details.install.{finalStatus,
        // postVerification.satisfied}. finalStatus disambiguates a satisfied=false
        // authoritative denial (FAILED_VERIFICATION) from a never-ran zero-value.
        Map<String, Object> redacted = new LinkedHashMap<>();
        Map<String, Object> install = new LinkedHashMap<>();
        if (finalStatus != null) {
            install.put("finalStatus", finalStatus);
        }
        Map<String, Object> postVerification = new LinkedHashMap<>();
        postVerification.put("satisfied", satisfied);
        install.put("postVerification", postVerification);
        redacted.put("install", install);
        return redacted;
    }

    private EndpointCommand installCommand(String preflightDecision, String preflightDecisionAt) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(TENANT_ID);
        command.setCommandType(CommandType.INSTALL_SOFTWARE);
        command.setIdempotencyKey("admin-install:test:" + UUID.randomUUID());
        command.setIssuedBySubject("alice@example.com");

        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_ID);
        try {
            // Set the device id reflectively because the entity uses
            // a Hibernate-managed UUID generator.
            var field = EndpointDevice.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(device, DEVICE_ID);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        command.setDevice(device);

        try {
            var idField = EndpointCommand.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(command, COMMAND_ID);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalogItemId", "7zip-stable");
        payload.put("catalogItemUuid", CATALOG_UUID.toString());
        payload.put("catalogPackageId", "7zip.7zip");
        payload.put("catalogRowVersion", 3L);
        if (preflightDecision != null) {
            payload.put("preflightDecision", preflightDecision);
        }
        if (preflightDecisionAt != null) {
            payload.put("preflightDecisionAt", preflightDecisionAt);
        }
        command.setPayload(payload);
        return command;
    }

    private static EndpointCommandResult dummyResult() {
        return new EndpointCommandResult();
    }

    private static AgentCommandResultRequest submitRequest() {
        return new AgentCommandResultRequest(
                "claim-1",
                1,
                CommandResultStatus.SUCCEEDED,
                "ok",
                null,
                null,
                null,
                0,
                NOW.minusSeconds(30),
                NOW);
    }

    private static Map<String, Object> redactedDetailsWithDetection() {
        // BE-028: canonical wire shape — the InstallResult under
        // `details.install`; the detected package/version are
        // postVerification.matchedPackageId / matchedVersion.
        Map<String, Object> redacted = new LinkedHashMap<>();
        Map<String, Object> install = new LinkedHashMap<>();
        install.put("finalStatus", "SUCCEEDED");
        install.put("exitCode", 0);
        Map<String, Object> postVerification = new LinkedHashMap<>();
        postVerification.put("status", "SATISFIED");
        postVerification.put("matchedPackageId", "7zip.7zip");
        postVerification.put("matchedVersion", "24.07");
        install.put("postVerification", postVerification);
        redacted.put("install", install);
        return redacted;
    }
}
