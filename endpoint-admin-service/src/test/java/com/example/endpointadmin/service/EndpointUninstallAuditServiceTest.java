package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointUninstallAudit;
import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;
import com.example.endpointadmin.model.UninstallResultStatus;
import com.example.endpointadmin.model.UninstallVerification;
import com.example.endpointadmin.repository.EndpointUninstallAuditRepository;
import com.example.endpointadmin.repository.EndpointUninstallRequestRepository;
import com.example.endpointadmin.security.UninstallEvidencePayloadPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-028 Phase 2B — unit tests for {@link EndpointUninstallAuditService}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>finalStatus → resultStatus mapping for every closed enum value
 *       (including unknown / null fallback to PARTIAL_INCONCLUSIVE).</li>
 *   <li>Verification cross-field consistency matrix (Codex 019e8f9c REVISE
 *     absorb): verification is forced DETERMINISTICALLY from resultStatus,
 *     NOT read independently from probeState. SUCCEEDED_VERIFIED /
 *     SKIP_ALREADY_ABSENT → ABSENT_VERIFIED; FAILED_VERIFY_GHOST →
 *     PRESENT_VERIFIED; PARTIAL_RESIDUE → RESIDUE_PRESENT;
 *     PARTIAL_INCONCLUSIVE / FAILED_PRECHECK_INCONCLUSIVE → VERIFY_INCONCLUSIVE;
 *     FAILED_UNSUPPORTED_* → NOT_RUN; FAILED_EXIT → deriveVerification CLAMPED
 *     (only MATCHED→PRESENT_VERIFIED survives, else VERIFY_INCONCLUSIVE).
 *     Contradiction cases (e.g. FAILED_VERIFY_GHOST+probeState=ABSENT) resolve
 *     to the status-consistent verification, never a contradictory verdict.
 *   </li>
 *   <li>Audit row written + Endpoint request transitioned to TERMINAL.</li>
 *   <li>BE-016 hash-chain audit event {@code ENDPOINT_UNINSTALL_RESULT_RECORDED}
 *       emitted.</li>
 *   <li>Wrong commandType → {@link IllegalStateException}.</li>
 *   <li>Missing requestId / catalogItemUuid in payload → {@link IllegalStateException}.</li>
 *   <li>Missing request row → {@link IllegalStateException}.</li>
 *   <li>Already-TERMINAL request → no double save.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EndpointUninstallAuditServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CATALOG_UUID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-06-04T12:00:00Z");

    @Mock private EndpointUninstallAuditRepository auditRepository;
    @Mock private EndpointUninstallRequestRepository requestRepository;
    @Mock private EndpointAuditService auditService;

    private UninstallEvidencePayloadPolicy policy;
    private EndpointUninstallAuditService service;

    @BeforeEach
    void setUp() {
        policy = new UninstallEvidencePayloadPolicy(
                UninstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
                UninstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);
        service = new EndpointUninstallAuditService(
                auditRepository, requestRepository, policy, auditService);
    }

    @Test
    void succeededVerified_writesAuditAndDerivesAbsentVerified() {
        EndpointUninstallRequest req = pendingApprovalRequest();
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.of(req));
        when(auditRepository.save(any(EndpointUninstallAudit.class)))
                .thenAnswer(inv -> stampId(inv.getArgument(0)));

        Map<String, Object> redacted = uninstallDetails("SUCCEEDED_VERIFIED", "ABSENT");

        EndpointUninstallAudit saved = service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), redacted, NOW);

        assertThat(saved.getResultStatus()).isEqualTo(UninstallResultStatus.SUCCEEDED_VERIFIED);
        assertThat(saved.getVerification()).isEqualTo(UninstallVerification.ABSENT_VERIFIED);
        assertThat(saved.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(saved.getCatalogItemId()).isEqualTo(CATALOG_UUID);
        assertThat(saved.getCommandId()).isEqualTo(COMMAND_ID);
        assertThat(saved.getRedactedPayload()).containsKey("uninstall");

        // Request lifecycle finalised.
        assertThat(req.getState()).isEqualTo(UninstallRequestState.TERMINAL);
        verify(requestRepository).save(req);

        // BE-016 hash-chain audit event emitted.
        verify(auditService).record(
                eq(TENANT_ID),
                any(EndpointDevice.class),
                eq(command),
                eq("ENDPOINT_UNINSTALL_RESULT_RECORDED"),
                eq("RECORD_UNINSTALL_RESULT"),
                any(),
                any(),
                any(),
                any(),
                any());
    }

    @Test
    void skipAlreadyAbsent_overridesVerificationToAbsentVerified() {
        // Even if the agent ships probeState=UNSUPPORTED (which deriveVerification
        // would map to VERIFY_INCONCLUSIVE), SKIP_ALREADY_ABSENT MUST surface as
        // ABSENT_VERIFIED. Pre-probe was the authoritative ABSENT; no mutation
        // happened, so absence is preserved.
        runMappingScenario(
                "SKIP_ALREADY_ABSENT",
                /*probeState*/ "UNSUPPORTED",
                UninstallResultStatus.SKIP_ALREADY_ABSENT,
                UninstallVerification.ABSENT_VERIFIED);
    }

    @Test
    void failedUnsupportedPlatform_overridesVerificationToNotRun() {
        runMappingScenario(
                "FAILED_UNSUPPORTED_PLATFORM",
                /*probeState*/ null,
                UninstallResultStatus.FAILED_UNSUPPORTED_PLATFORM,
                UninstallVerification.NOT_RUN);
    }

    @Test
    void failedUnsupportedVerification_overridesVerificationToNotRun() {
        runMappingScenario(
                "FAILED_UNSUPPORTED_VERIFICATION",
                /*probeState*/ "UNSUPPORTED",
                UninstallResultStatus.FAILED_UNSUPPORTED_VERIFICATION,
                UninstallVerification.NOT_RUN);
    }

    @Test
    void partialResidue_derivesResiduePresent() {
        runMappingScenario(
                "PARTIAL_RESIDUE",
                "PRESENT_MISMATCH",
                UninstallResultStatus.PARTIAL_RESIDUE,
                UninstallVerification.RESIDUE_PRESENT);
    }

    @Test
    void failedVerifyGhost_derivesPresentVerified() {
        runMappingScenario(
                "FAILED_VERIFY_GHOST",
                "MATCHED",
                UninstallResultStatus.FAILED_VERIFY_GHOST,
                UninstallVerification.PRESENT_VERIFIED);
    }

    @Test
    void failedExit_derivesPresentVerifiedWhenAgentSawMatch() {
        runMappingScenario(
                "FAILED_EXIT",
                "MATCHED",
                UninstallResultStatus.FAILED_EXIT,
                UninstallVerification.PRESENT_VERIFIED);
    }

    @Test
    void failedPrecheckInconclusive_derivesVerifyInconclusive() {
        runMappingScenario(
                "FAILED_PRECHECK_INCONCLUSIVE",
                "ERROR",
                UninstallResultStatus.FAILED_PRECHECK_INCONCLUSIVE,
                UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void unknownFinalStatus_fallsBackToPartialInconclusiveAndVerifyInconclusive() {
        // Agent ships a literal not in the closed UninstallResultStatus
        // allow-list, with probeState=ABSENT. Codex 019e8f9c REVISE absorb:
        // verification is forced from resultStatus, NOT read independently from
        // probeState — so protocol drift can NEVER certify ABSENT_VERIFIED.
        // resultStatus → fail-closed PARTIAL_INCONCLUSIVE, which forces
        // verification → VERIFY_INCONCLUSIVE.
        runMappingScenario(
                "SUCCEEDED_BUT_DRIFTED",
                "ABSENT",
                EndpointUninstallAuditService.FALLBACK_RESULT_STATUS,
                UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    // ── Codex 019e8f9c REVISE: cross-field consistency clamps ──────────

    @Test
    void contradictoryGhostWithAbsentProbe_forcesPresentVerified() {
        // FAILED_VERIFY_GHOST = winget exit=0 but package STILL detected. If a
        // drifted agent also ships probeState=ABSENT (contradiction), the
        // verification MUST stay PRESENT_VERIFIED (forced from resultStatus),
        // never ABSENT_VERIFIED.
        runMappingScenario(
                "FAILED_VERIFY_GHOST",
                "ABSENT",
                UninstallResultStatus.FAILED_VERIFY_GHOST,
                UninstallVerification.PRESENT_VERIFIED);
    }

    @Test
    void contradictorySucceededWithMatchedProbe_forcesAbsentVerified() {
        // SUCCEEDED_VERIFIED is the agent's authoritative "removed + verified
        // absent" classification. Even if a drifted probeState=MATCHED rides
        // along, verification is forced to ABSENT_VERIFIED (consistent with the
        // status), not independently derived to PRESENT_VERIFIED.
        runMappingScenario(
                "SUCCEEDED_VERIFIED",
                "MATCHED",
                UninstallResultStatus.SUCCEEDED_VERIFIED,
                UninstallVerification.ABSENT_VERIFIED);
    }

    @Test
    void contradictoryResidueWithAbsentProbe_forcesResiduePresent() {
        runMappingScenario(
                "PARTIAL_RESIDUE",
                "ABSENT",
                UninstallResultStatus.PARTIAL_RESIDUE,
                UninstallVerification.RESIDUE_PRESENT);
    }

    @Test
    void failedExitWithAbsentProbe_clampsToVerifyInconclusive() {
        // A non-zero exit must NEVER read as ABSENT_VERIFIED. Even if the
        // probeState somehow resolves to ABSENT, the FAILED_EXIT clamp forces
        // VERIFY_INCONCLUSIVE.
        runMappingScenario(
                "FAILED_EXIT",
                "ABSENT",
                UninstallResultStatus.FAILED_EXIT,
                UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void failedExitWithErrorProbe_clampsToVerifyInconclusive() {
        runMappingScenario(
                "FAILED_EXIT",
                "ERROR",
                UninstallResultStatus.FAILED_EXIT,
                UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void missingFinalStatus_fallsBackToPartialInconclusiveAndVerifyInconclusive() {
        // No `uninstall` block at all → deriveVerification returns
        // VERIFY_INCONCLUSIVE, and finalStatus null → PARTIAL_INCONCLUSIVE.
        EndpointUninstallRequest req = pendingApprovalRequest();
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.of(req));
        when(auditRepository.save(any(EndpointUninstallAudit.class)))
                .thenAnswer(inv -> stampId(inv.getArgument(0)));

        EndpointUninstallAudit saved = service.recordUninstallResult(
                command, dummyResult(), submitRequest(0),
                /*redactedDetails*/ null, NOW);

        assertThat(saved.getResultStatus())
                .isEqualTo(EndpointUninstallAuditService.FALLBACK_RESULT_STATUS);
        assertThat(saved.getVerification())
                .isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void wrongCommandType_rejectsWithIllegalState() {
        EndpointCommand command = uninstallCommand();
        command.setCommandType(CommandType.INSTALL_SOFTWARE);

        assertThatThrownBy(() -> service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-UNINSTALL_SOFTWARE");
    }

    @Test
    void missingRequestIdInPayload_rejectsWithIllegalState() {
        EndpointCommand command = uninstallCommand();
        Map<String, Object> payload = new HashMap<>(command.getPayload());
        payload.remove("requestId");
        command.setPayload(payload);

        assertThatThrownBy(() -> service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void missingCatalogItemUuidInPayload_rejectsWithIllegalState() {
        EndpointCommand command = uninstallCommand();
        Map<String, Object> payload = new HashMap<>(command.getPayload());
        payload.remove("catalogItemUuid");
        command.setPayload(payload);

        assertThatThrownBy(() -> service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalogItemUuid");
    }

    @Test
    void missingRequestRow_rejectsWithIllegalState() {
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), Map.of(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references missing request");
    }

    @Test
    void alreadyTerminalRequest_doesNotResaveRequest() {
        EndpointUninstallRequest req = pendingApprovalRequest();
        req.setState(UninstallRequestState.TERMINAL); // already final
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.of(req));
        when(auditRepository.save(any(EndpointUninstallAudit.class)))
                .thenAnswer(inv -> stampId(inv.getArgument(0)));

        service.recordUninstallResult(
                command, dummyResult(), submitRequest(0),
                uninstallDetails("SUCCEEDED_VERIFIED", "ABSENT"), NOW);

        verify(auditRepository, times(1)).save(any(EndpointUninstallAudit.class));
        // request was already TERMINAL → no save invoked again.
        verify(requestRepository, never()).save(any(EndpointUninstallRequest.class));
    }

    @Test
    void detectionEvidence_surfacesProbeStateAndAuthority() {
        EndpointUninstallRequest req = pendingApprovalRequest();
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.of(req));
        ArgumentCaptor<EndpointUninstallAudit> captor =
                ArgumentCaptor.forClass(EndpointUninstallAudit.class);
        when(auditRepository.save(captor.capture()))
                .thenAnswer(inv -> stampId(inv.getArgument(0)));

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("authority", "REGISTRY_UNINSTALL");
        Map<String, Object> safeEvidence = new LinkedHashMap<>();
        safeEvidence.put("ruleType", "REGISTRY_UNINSTALL");
        safeEvidence.put("matchedPackageId", "Microsoft.7Zip");
        uninstall.put("safeEvidence", safeEvidence);
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("uninstall", uninstall);
        // run through the policy so the projection allow-list applies.
        redacted = policy.redact(redacted);

        service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), redacted, NOW);

        Map<String, Object> evidence = captor.getValue().getDetectionEvidence();
        assertThat(evidence).containsKey("ruleType");
        assertThat(evidence).containsKey("probeState");
        assertThat(evidence).containsEntry("authority", "REGISTRY_UNINSTALL");
    }

    // ────────────────────────────────────────────────────────────────
    // helpers

    private void runMappingScenario(
            String wireFinalStatus,
            String probeState,
            UninstallResultStatus expectedStatus,
            UninstallVerification expectedVerification) {
        EndpointUninstallRequest req = pendingApprovalRequest();
        EndpointCommand command = uninstallCommand();
        when(requestRepository.findByTenantIdAndId(TENANT_ID, REQUEST_ID))
                .thenReturn(Optional.of(req));
        when(auditRepository.save(any(EndpointUninstallAudit.class)))
                .thenAnswer(inv -> stampId(inv.getArgument(0)));

        Map<String, Object> redacted = uninstallDetails(wireFinalStatus, probeState);

        EndpointUninstallAudit saved = service.recordUninstallResult(
                command, dummyResult(), submitRequest(0), redacted, NOW);

        assertThat(saved.getResultStatus())
                .as("agent finalStatus=%s → resultStatus", wireFinalStatus)
                .isEqualTo(expectedStatus);
        assertThat(saved.getVerification())
                .as("agent finalStatus=%s, probeState=%s → verification",
                        wireFinalStatus, probeState)
                .isEqualTo(expectedVerification);
    }

    private static Map<String, Object> uninstallDetails(String finalStatus, String probeState) {
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", finalStatus);
        if (probeState != null) {
            uninstall.put("probeState", probeState);
        }
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("uninstall", uninstall);
        return top;
    }

    private static EndpointUninstallRequest pendingApprovalRequest() {
        EndpointUninstallRequest r = new EndpointUninstallRequest();
        r.setId(REQUEST_ID);
        r.setTenantId(TENANT_ID);
        r.setDeviceId(DEVICE_ID);
        r.setCatalogItemId(CATALOG_UUID);
        r.setState(UninstallRequestState.APPROVED); // approved at this stage
        r.setCreatedBy("alice");
        r.setApprovedBy("bob");
        r.setCreatedAt(NOW.minusSeconds(60));
        r.setStateUpdatedAt(NOW.minusSeconds(60));
        return r;
    }

    private static EndpointCommand uninstallCommand() {
        EndpointCommand command = new EndpointCommand();
        setField(command, "id", COMMAND_ID);
        command.setTenantId(TENANT_ID);
        EndpointDevice device = new EndpointDevice();
        setField(device, "id", DEVICE_ID);
        command.setDevice(device);
        command.setCommandType(CommandType.UNINSTALL_SOFTWARE);
        command.setIdempotencyKey("admin-uninstall-cmd:" + REQUEST_ID);
        command.setIssuedBySubject("bob");
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", REQUEST_ID.toString());
        payload.put("catalogItemUuid", CATALOG_UUID.toString());
        payload.put("intent", "UNINSTALL");
        payload.put("argsPolicyPreset", "UNINSTALL_DEFAULT");
        command.setPayload(payload);
        return command;
    }

    private static EndpointCommandResult dummyResult() {
        EndpointCommandResult result = new EndpointCommandResult();
        setField(result, "id", UUID.randomUUID());
        return result;
    }

    private static EndpointUninstallAudit stampId(EndpointUninstallAudit a) {
        // Mockito save() does NOT trigger JPA @PrePersist; assign a UUID
        // explicitly so the downstream auditService.record(...) metadata
        // map can call saved.getId().toString() without NPE.
        if (a.getId() == null) {
            a.setId(UUID.randomUUID());
        }
        return a;
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

    private static AgentCommandResultRequest submitRequest(Integer exitCode) {
        return new AgentCommandResultRequest(
                "claim-001",
                /*attemptNumber*/ 1,
                CommandResultStatus.SUCCEEDED,
                /*summary*/ "uninstall complete",
                /*details*/ null,
                /*errorCode*/ null,
                /*errorMessage*/ null,
                /*exitCode*/ exitCode,
                /*startedAt*/ NOW.minusSeconds(10),
                /*finishedAt*/ NOW);
    }
}
