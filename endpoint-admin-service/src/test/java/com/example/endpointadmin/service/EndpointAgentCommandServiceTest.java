package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointAgentCommandService.class,
        EndpointAuditService.class,
        com.example.endpointadmin.audit.NoOpAuditChainLock.class,
        EndpointSoftwareInventoryService.class,
        com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy.class,
        // BE-021A — the inventory service now also depends on the
        // wingetEgress validator (fail-closed schema + PII guard).
        com.example.endpointadmin.security.WinGetEgressPayloadPolicy.class,
        // BE-021 — install audit dependencies for the INSTALL_SOFTWARE
        // terminal-result branch in submitResult.
        com.example.endpointadmin.security.InstallEvidencePayloadPolicy.class,
        EndpointInstallAuditService.class,
        // BE-022 — hardware sanitizer + service wired into the
        // COLLECT_INVENTORY pre-persist + ingest path
        // (Codex 019e7007 iter-4 absorb).
        com.example.endpointadmin.security.HardwareInventoryPayloadPolicy.class,
        EndpointHardwareInventoryService.class,
        // BE — device-health (AG-033) sanitizer + service wired into the
        // same COLLECT_INVENTORY pre-persist + ingest path.
        com.example.endpointadmin.security.DeviceHealthPayloadPolicy.class,
        EndpointDeviceHealthService.class
})
class EndpointAgentCommandServiceTest {

    @Autowired
    private EndpointAgentCommandService commandService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Test
    void claimNextClaimsOldestVisibleQueuedCommandForAuthenticatedDevice() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-1", 10));
        commandRepository.saveAndFlush(command(device, "cmd-2", 20));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(device));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().commandId()).isEqualTo(command.getId());
        assertThat(response.orElseThrow().type()).isEqualTo(CommandType.COLLECT_INVENTORY);
        assertThat(response.orElseThrow().attemptNumber()).isEqualTo(1);
        assertThat(response.orElseThrow().claimId()).isNotBlank();
        assertThat(response.orElseThrow().reason()).isEqualTo("inventory refresh");
        assertThat(response.orElseThrow().claimExpiresAt()).isNotNull();

        EndpointCommand updated = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommandStatus.DELIVERED);
        assertThat(updated.getLockedBy()).isEqualTo(response.orElseThrow().claimId());
        assertThat(updated.getLockedUntil()).isEqualTo(response.orElseThrow().claimExpiresAt());
        assertThat(updated.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void claimNextDoesNotClaimAnotherDevicesCommand() {
        EndpointDevice first = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-002"));
        commandRepository.saveAndFlush(command(second, "cmd-other", 10));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(first));

        assertThat(response).isEmpty();
    }

    @Test
    void submitResultStoresResultAndFinalizesCommand() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-result", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();
        AgentCommandResultRequest request = resultRequest(
                claimed.claimId(),
                claimed.attemptNumber(),
                CommandResultStatus.SUCCEEDED
        );

        commandService.submitResult(principal(device), command.getId(), request);
        commandService.submitResult(principal(device), command.getId(), request);

        EndpointCommand updated = commandRepository.findById(command.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getLockedBy()).isNull();
        assertThat(updated.getLockedUntil()).isNull();

        EndpointCommandResult result = resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(result.getResultPayload())
                .containsEntry("summary", "done")
                .containsEntry("claimId", claimed.claimId())
                .containsEntry("attemptNumber", claimed.attemptNumber())
                .containsKey("details");
    }

    // ------------------------------------------------------------------
    // BE-022 hardware sanitize-before-validate + ingest hook integration
    // (Codex 019e7007 iter-4 absorb).
    // ------------------------------------------------------------------

    @Autowired
    private com.example.endpointadmin.repository.EndpointHardwareInventorySnapshotRepository hardwareSnapshotRepository;

    @Autowired
    private com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository softwareSnapshotRepository;

    @Test
    void submitResultIngestsBothSoftwareAndHardwareForCollectInventoryWithBothBlocks() {
        // Codex 019e7007 iter-3 must-fix: prove DUAL software + hardware
        // ingest in a single submitResult call. The request now carries
        // both `inventory.software` and `inventory.hardware`; assert
        // both snapshot tables receive a row.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-HW-1"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-hw-1", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> hardware = new java.util.LinkedHashMap<>();
        hardware.put("schemaVersion", 1);
        hardware.put("supported", true);
        hardware.put("cpuModel", "Intel i7-1260P");
        hardware.put("ramTotalBytes", 16000000000L);
        hardware.put("collectedAt",
                java.time.Instant.now().minusSeconds(60).toString());
        hardware.put("disks", java.util.List.of());
        hardware.put("networkInterfaces", java.util.List.of());

        java.util.Map<String, Object> software = new java.util.LinkedHashMap<>();
        software.put("schemaVersion", 1);
        software.put("supported", true);
        software.put("appCount", 0);
        software.put("wingetReady", true);
        software.put("apps", java.util.List.of());

        java.util.Map<String, Object> inventory = new java.util.LinkedHashMap<>();
        inventory.put("software", software);
        inventory.put("hardware", hardware);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("inventory", inventory);

        commandService.submitResult(
                principal(device),
                command.getId(),
                new AgentCommandResultRequest(
                        claimed.claimId(),
                        claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED,
                        "done",
                        details,
                        null, null, 0,
                        Instant.now().minusSeconds(5),
                        Instant.now()));

        EndpointCommandResult result = resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);

        // SOFTWARE snapshot persisted (BE-020I path).
        assertThat(softwareSnapshotRepository.count())
                .as("software inventory snapshot row count")
                .isEqualTo(1);

        // HARDWARE snapshot persisted (BE-022 path) with the
        // source_command_result_id pointer to this same result row.
        assertThat(hardwareSnapshotRepository.count())
                .as("hardware inventory snapshot row count")
                .isEqualTo(1);
        var snapshot = hardwareSnapshotRepository.findBySourceCommandResultId(result.getId());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getDeviceId()).isEqualTo(device.getId());
    }

    @Test
    void submitResultSoftwareOnlyDoesNotIngestHardware() {
        // hasHardwareBlock gate: software-only COLLECT_INVENTORY
        // results must NOT touch the hardware service / table.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-SW-ONLY"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-sw-only", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        long beforeHardware = hardwareSnapshotRepository.count();

        commandService.submitResult(
                principal(device),
                command.getId(),
                new AgentCommandResultRequest(
                        claimed.claimId(),
                        claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED,
                        "done",
                        java.util.Map.of("inventory", java.util.Map.of(
                                "software", java.util.Map.of(
                                        "schemaVersion", 1,
                                        "supported", true,
                                        "appCount", 0,
                                        "wingetReady", true,
                                        "apps", java.util.List.of()))),
                        null, null, 0,
                        Instant.now().minusSeconds(5),
                        Instant.now()));

        // Software ingest fired; hardware table unchanged.
        assertThat(hardwareSnapshotRepository.count()).isEqualTo(beforeHardware);
    }

    @Test
    void submitResultRejectsHardwareValueLevelBearerLeak() {
        // Codex 019e7007 iter-1 must-fix #3 — value-level secret
        // pattern fail-closed. A probeError summary carrying
        // "Bearer eyJ..." rolls back the entire submitResult
        // transaction (400 BAD_REQUEST).
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-BEARER"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-bearer", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> hw = new java.util.LinkedHashMap<>();
        hw.put("schemaVersion", 1);
        hw.put("supported", true);
        hw.put("collectedAt", java.time.Instant.now().minusSeconds(60).toString());
        java.util.Map<String, Object> probeError = new java.util.LinkedHashMap<>();
        probeError.put("code", "AUTH_FAIL");
        probeError.put("summary",
                "auth header rejected: Bearer abcdef0123456789abcdef0123456789");
        hw.put("probeErrors", java.util.List.of(probeError));

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithHardware(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, hw)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Secret value pattern");

        // Transaction rolled back: no command result + no hardware
        // snapshot persisted.
        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(hardwareSnapshotRepository.count()).isEqualTo(0);
    }

    @Test
    void submitResultSanitizesHardwareBeforeSoftwarePolicyValidates() {
        // Regression for sanitize-before-validate ordering:
        // hardware sub-tree's user path is stripped by the hardware
        // sanitizer; the software validator (which would otherwise
        // fail-closed reject "C:\Users\..." anywhere) sees the
        // redacted form and lets the result persist.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-ORDER"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-order", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> hw = new java.util.LinkedHashMap<>();
        hw.put("schemaVersion", 1);
        hw.put("supported", true);
        hw.put("collectedAt", java.time.Instant.now().minusSeconds(60).toString());
        // Hardware probe summary carries a user path — sanitizer
        // strips it BEFORE the software policy runs.
        java.util.Map<String, Object> probeError = new java.util.LinkedHashMap<>();
        probeError.put("code", "PROBE_USER_PATH");
        probeError.put("summary",
                "discovered at C:\\Users\\alice\\AppData\\Local\\probe.log");
        hw.put("probeErrors", java.util.List.of(probeError));

        // No exception expected — sanitizer replaced the user path
        // with <redacted>, then the software validator saw a clean
        // payload.
        commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequestWithHardware(
                        claimed.claimId(), claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED, hw));

        EndpointCommandResult result =
                resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);

        // result_payload.details.inventory.hardware.probeErrors[].summary
        // must NOT contain "alice" — sanitizer stripped it pre-persist
        // (redaction round-trip evidence).
        Object resultPayload = result.getResultPayload();
        assertThat(resultPayload.toString()).doesNotContain("alice");
    }

    /**
     * Build an AgentCommandResultRequest carrying a hardware sub-tree
     * under {@code details.inventory.hardware}.
     */
    private AgentCommandResultRequest resultRequestWithHardware(
            String claimId, int attemptNumber, CommandResultStatus status,
            java.util.Map<String, Object> hardware) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> inventory = new java.util.LinkedHashMap<>();
        inventory.put("hardware", hardware);
        details.put("inventory", inventory);
        return new AgentCommandResultRequest(
                claimId,
                attemptNumber,
                status,
                "done",
                details,
                null, null, 0,
                Instant.now().minusSeconds(5),
                Instant.now());
    }

    @Test
    void submitResultRejectsWrongClaim() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-001"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-wrong-claim", 10));
        commandService.claimNext(principal(device)).orElseThrow();

        assertThatThrownBy(() -> commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequest("wrong-claim", 1, CommandResultStatus.FAILED)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Command claim is not valid.");
    }

    private DeviceCredentialResult principal(EndpointDevice device) {
        return new DeviceCredentialResult(device.getId().toString(), UUID.randomUUID().toString(), Instant.now());
    }

    private EndpointDevice device(DeviceStatus status, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(UUID.randomUUID());
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.2.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(status);
        device.setLastSeenAt(Instant.now());
        return device;
    }

    private EndpointCommand command(EndpointDevice device, String idempotencyKey, int priority) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(device.getTenantId());
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setPayload(Map.of("reason", "inventory refresh", "requestedDetail", "basic"));
        command.setPriority(priority);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setIssuedBySubject("admin@example.com");
        command.setVisibleAfterAt(Instant.now().minusSeconds(60));
        command.setIssuedAt(Instant.now().minusSeconds(120));
        return command;
    }

    private AgentCommandResultRequest resultRequest(String claimId,
                                                    int attemptNumber,
                                                    CommandResultStatus status) {
        return new AgentCommandResultRequest(
                claimId,
                attemptNumber,
                status,
                "done",
                Map.of("inventory", Map.of("hostname", "PC-001")),
                null,
                null,
                0,
                Instant.now().minusSeconds(5),
                Instant.now()
        );
    }
}
