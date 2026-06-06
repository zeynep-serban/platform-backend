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
        EndpointDeviceHealthService.class,
        // AG-036 — outdated-software sanitizer + service wired into the same
        // COLLECT_INVENTORY pre-persist + ingest path.
        com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy.class,
        EndpointOutdatedSoftwareService.class,
        // AG-037 — hotfix-posture sanitizer + service wired into the same
        // COLLECT_INVENTORY pre-persist + ingest path.
        com.example.endpointadmin.security.HotfixPosturePayloadPolicy.class,
        EndpointHotfixPostureService.class,
        // AG-038-be — diagnostics ingest service + policy (Faz 22.5).
        com.example.endpointadmin.security.DiagnosticsPayloadPolicy.class,
        EndpointDiagnosticsService.class,
        // AG-039-be — critical services ingest service + policy (Faz 22.5).
        com.example.endpointadmin.security.ServicesPayloadPolicy.class,
        EndpointServicesService.class,
        // AG-040-be — startup-exposure ingest service + policy (Faz 22.5).
        com.example.endpointadmin.security.StartupExposurePayloadPolicy.class,
        EndpointStartupExposureService.class,
        // AG-041-be — Application Control (WDAC + AppLocker) ingest service + policy (Faz 22.5).
        com.example.endpointadmin.security.AppControlPayloadPolicy.class,
        EndpointAppControlService.class,
        // AG-028 Phase 2B — uninstall evidence sanitiser + audit service wired
        // into the UNINSTALL_SOFTWARE submitResult branch (Faz 22.5.6,
        // Codex 019e8de2 iter-2 absorb).
        com.example.endpointadmin.security.UninstallEvidencePayloadPolicy.class,
        EndpointUninstallAuditService.class
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
    void claimNextClaimsInstallWhenTenantThrottleHasCapacity() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-INSTALL-001"));
        EndpointCommand install = commandRepository.saveAndFlush(
                installCommand(device, "install-capacity", 10));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(device));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().commandId()).isEqualTo(install.getId());
        assertThat(response.orElseThrow().type()).isEqualTo(CommandType.INSTALL_SOFTWARE);
        EndpointCommand updated = commandRepository.findById(install.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommandStatus.DELIVERED);
        assertThat(updated.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void claimNextDoesNotClaimInstallWhenTenantThrottleIsSaturated() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-INSTALL-002"));
        EndpointDevice other = device(DeviceStatus.ONLINE, "PC-INSTALL-OTHER");
        other.setTenantId(device.getTenantId());
        other = deviceRepository.saveAndFlush(other);
        commandRepository.saveAndFlush(activeInstallCommand(other, "install-active", 10));
        EndpointCommand waitingInstall = commandRepository.saveAndFlush(
                installCommand(device, "install-waiting", 20));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(device));

        assertThat(response).isEmpty();
        EndpointCommand reloaded = commandRepository.findById(waitingInstall.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(reloaded.getLockedBy()).isNull();
        assertThat(reloaded.getAttemptCount()).isZero();
    }

    @Test
    void claimNextSkipsThrottledInstallAndClaimsNextNonInstallCommand() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-INSTALL-003"));
        EndpointDevice other = device(DeviceStatus.ONLINE, "PC-INSTALL-OTHER-2");
        other.setTenantId(device.getTenantId());
        other = deviceRepository.saveAndFlush(other);
        commandRepository.saveAndFlush(activeInstallCommand(other, "install-active-2", 10));
        EndpointCommand throttledInstall = commandRepository.saveAndFlush(
                installCommand(device, "install-top-priority", 5));
        EndpointCommand inventory = commandRepository.saveAndFlush(
                command(device, "cmd-behind-install", 10));

        Optional<AgentCommandResponse> response = commandService.claimNext(principal(device));

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().commandId()).isEqualTo(inventory.getId());
        assertThat(response.orElseThrow().type()).isEqualTo(CommandType.COLLECT_INVENTORY);
        EndpointCommand installReloaded = commandRepository.findById(throttledInstall.getId()).orElseThrow();
        assertThat(installReloaded.getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(installReloaded.getLockedBy()).isNull();
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

    @Autowired
    private com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository deviceHealthSnapshotRepository;

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

    // ------------------------------------------------------------------
    // Security regression — redaction-boundary type-confusion bypass.
    // A present-but-non-Map deviceHealth / hardware block (a List / String
    // / scalar) used to skip BOTH the Map-gated per-field sanitize AND the
    // Map-only ingest gate, leaking "NEVER on the wire" PII into
    // endpoint_command_results.result_payload.details with no 400. The
    // fail-closed fix (mirroring WinGetEgressPayloadPolicy.validate) now
    // rejects it with 400 + full transaction rollback. Asserted here via
    // the REAL sink (submitResult), not just the policy unit.
    // ------------------------------------------------------------------

    @Test
    void submitResultRejectsDeviceHealthAsListCarryingForbiddenPii() {
        // The live-leak repro (device-health LIVE since BE #332): a
        // `deviceHealth` LIST carrying the device-health "NEVER on the
        // wire" identifiers (serial / volumeLabel / mountPath / guid).
        // Pre-fix this bypassed the redaction boundary entirely and
        // persisted to result_payload.details with no 400.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-DH-LIST"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-dh-list", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> leak = new java.util.LinkedHashMap<>();
        leak.put("serial", "WD-WX12A34567B9");
        leak.put("volumeLabel", "OS");
        leak.put("mountPath", "C:\\Mounts\\disk0");
        leak.put("guid", "{12345678-1234-1234-1234-1234567890ab}");
        Object deviceHealthAsList = java.util.List.of(leak);

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithInventoryDeviceHealth(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, deviceHealthAsList)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.inventory.deviceHealth")
                .hasMessageContaining("must be an object");

        // Transaction rolled back: no command result row, no device-health
        // snapshot. The forbidden identifiers never touched any sink.
        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(deviceHealthSnapshotRepository.count()).isEqualTo(0);
    }

    @Test
    void submitResultRejectsDeviceHealthAsString() {
        // A `deviceHealth` STRING scalar must also fail-closed (not just a
        // List) — any present-but-non-Map shape bypasses the Map gate.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-DH-STR"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-dh-str", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithInventoryDeviceHealth(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED,
                                "serial=WD-WX12A34567B9 volumeLabel=OS")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.inventory.deviceHealth")
                .hasMessageContaining("must be an object");

        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(deviceHealthSnapshotRepository.count()).isEqualTo(0);
    }

    @Test
    void submitResultRejectsTopLevelDeviceHealthAliasAsList() {
        // The top-level `$.deviceHealth` alias (accepted by some agent
        // versions) must enforce the same non-Map reject as the nested
        // `$.inventory.deviceHealth` path.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-DH-TOP"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-dh-top", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> leak = new java.util.LinkedHashMap<>();
        leak.put("serial", "WD-WX12A34567B9");
        leak.put("mountPath", "C:\\Mounts\\disk0");
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("deviceHealth", java.util.List.of(leak));

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        new AgentCommandResultRequest(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, "done", details,
                                null, null, 0,
                                Instant.now().minusSeconds(5), Instant.now())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.deviceHealth")
                .hasMessageContaining("must be an object");

        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(deviceHealthSnapshotRepository.count()).isEqualTo(0);
    }

    @Test
    void submitResultRejectsHardwareAsListCarryingRawSerialAndMac() {
        // Hardware sibling: a `hardware` LIST carrying a raw serial and an
        // unnormalized MAC bypassed the serial-STRIP / MAC-normalization
        // redaction pre-fix. Now fail-closed.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-HW-LIST"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-hw-list", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> leak = new java.util.LinkedHashMap<>();
        leak.put("biosSerial", "BIOS-SN-0099887766");
        leak.put("macAddress", "AA-BB-CC-DD-EE-FF");
        Object hardwareAsList = java.util.List.of(leak);

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithInventoryHardware(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, hardwareAsList)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.inventory.hardware")
                .hasMessageContaining("must be an object");

        // Transaction rolled back: no command result row, no hardware
        // snapshot. The raw serial / MAC never touched any sink.
        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(hardwareSnapshotRepository.count()).isEqualTo(0);
    }

    @Test
    void submitResultStillPersistsAndSanitizesValidDeviceHealthMap() {
        // Positive control / no-regression: a valid deviceHealth MAP still
        // persists the command result + the device-health snapshot.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-DH-OK"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-dh-ok", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequestWithInventoryDeviceHealth(
                        claimed.claimId(), claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED, goldenHealthyDeviceHealth()));

        EndpointCommandResult result =
                resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        // Device-health snapshot persisted (sanitize + ingest ran on the Map).
        assertThat(deviceHealthSnapshotRepository.count())
                .as("device-health snapshot row count")
                .isEqualTo(1);
        assertThat(deviceHealthSnapshotRepository.findBySourceCommandResultId(result.getId()))
                .isPresent();
    }

    @Test
    void submitResultStillPersistsAndSanitizesValidHardwareMap() {
        // Positive control / no-regression: a valid hardware MAP still
        // persists the command result + the hardware snapshot, with the
        // BIOS serial stripped to <redacted> (sanitize round-trip).
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-HW-OK"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-hw-ok", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> hardware = new java.util.LinkedHashMap<>();
        hardware.put("schemaVersion", 1);
        hardware.put("supported", true);
        hardware.put("biosSerial", "BIOS-SN-0099887766");
        hardware.put("collectedAt", java.time.Instant.now().minusSeconds(60).toString());
        hardware.put("disks", java.util.List.of());
        hardware.put("networkInterfaces", java.util.List.of());

        commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequestWithInventoryHardware(
                        claimed.claimId(), claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED, hardware));

        EndpointCommandResult result =
                resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(hardwareSnapshotRepository.count())
                .as("hardware snapshot row count")
                .isEqualTo(1);
        // BIOS serial stripped pre-persist — redaction round-trip evidence.
        assertThat(result.getResultPayload().toString())
                .doesNotContain("BIOS-SN-0099887766")
                .contains("<redacted>");
    }

    /**
     * Build an AgentCommandResultRequest whose {@code details.inventory.deviceHealth}
     * carries an arbitrary value (Map for the positive control; List /
     * String for the bypass repros).
     */
    private AgentCommandResultRequest resultRequestWithInventoryDeviceHealth(
            String claimId, int attemptNumber, CommandResultStatus status,
            Object deviceHealth) {
        java.util.Map<String, Object> inventory = new java.util.LinkedHashMap<>();
        inventory.put("deviceHealth", deviceHealth);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("inventory", inventory);
        return new AgentCommandResultRequest(
                claimId, attemptNumber, status, "done", details,
                null, null, 0,
                Instant.now().minusSeconds(5), Instant.now());
    }

    /**
     * Build an AgentCommandResultRequest whose {@code details.inventory.hardware}
     * carries an arbitrary value (Map for the positive control; List /
     * String for the bypass repros).
     */
    private AgentCommandResultRequest resultRequestWithInventoryHardware(
            String claimId, int attemptNumber, CommandResultStatus status,
            Object hardware) {
        java.util.Map<String, Object> inventory = new java.util.LinkedHashMap<>();
        inventory.put("hardware", hardware);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("inventory", inventory);
        return new AgentCommandResultRequest(
                claimId, attemptNumber, status, "done", details,
                null, null, 0,
                Instant.now().minusSeconds(5), Instant.now());
    }

    /**
     * A contract-valid healthy device-health Map (mirrors the golden
     * fixture in EndpointDeviceHealthServiceTest) for the positive control.
     */
    private static java.util.Map<String, Object> goldenHealthyDeviceHealth() {
        java.util.Map<String, Object> disk = new java.util.LinkedHashMap<>();
        disk.put("driveLetter", "C:");
        disk.put("totalBytes", 536870912000L);
        disk.put("freeBytes", 268435456000L);
        disk.put("freePercent", 50);
        disk.put("lowDiskWarning", false);

        java.util.Map<String, Object> memory = new java.util.LinkedHashMap<>();
        memory.put("totalPhysicalBytes", 17179869184L);
        memory.put("availableBytes", 9663676416L);
        memory.put("usedPercent", 42);
        memory.put("highPressureWarning", false);
        memory.put("commitLimitBytes", 25769803776L);
        memory.put("commitUsedBytes", 10307921920L);

        java.util.Map<String, Object> uptime = new java.util.LinkedHashMap<>();
        uptime.put("lastBootEpochSec", 1748275200L);
        uptime.put("uptimeSeconds", 259200L);
        uptime.put("uptimeDays", 3);
        uptime.put("longUptimeWarning", false);

        java.util.Map<String, Object> dh = new java.util.LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        dh.put("fixedDisks", java.util.List.of(disk));
        dh.put("fixedDiskCount", 1);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory);
        dh.put("uptime", uptime);
        dh.put("anyLowDisk", false);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 12);
        return dh;
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

    // ------------------------------------------------------------------
    // AG-036 outdated-software type-confusion security regression lock
    // (Codex 019e7693 RED P0). A present-but-non-Map outdatedSoftware
    // block (List / String) MUST fail-closed the submit so the raw,
    // unredacted block never reaches result_payload.details and no
    // outdated-software snapshot is appended. P1: a case-variant extra
    // package key violates additionalProperties:false and is rejected.
    // ------------------------------------------------------------------

    @Autowired
    private com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository outdatedSnapshotRepository;

    @Test
    void submitResultRejectsOutdatedSoftwareAsListAndPersistsNothing() {
        // The leak repro: outdatedSoftware is a LIST of raw package objects
        // carrying publisher / downloadUrl PII. Before the fix the policy's
        // `instanceof Map` dispatch had no else-throw, so the list passed
        // through unsanitized into result_payload.details and the
        // hasOutdatedSoftwareBlock(Map-only) gate skipped ingest — the raw
        // PII-bearing payload persisted. Now it is a fail-closed 400.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-OSW-LIST"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-osw-list", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> rawPkg = new java.util.LinkedHashMap<>();
        rawPkg.put("packageId", "Acme.Tool");
        rawPkg.put("installedVersion", "1.0.0");
        rawPkg.put("availableVersion", "2.0.0");
        rawPkg.put("publisher", "Acme Corp");
        rawPkg.put("downloadUrl", "http://acme.example/installer.exe");

        long beforeOutdated = outdatedSnapshotRepository.count();

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithOutdatedSoftware(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED,
                                java.util.List.of(rawPkg))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.inventory.outdatedSoftware");

        // Transaction rolled back: no command-result row, no outdated
        // snapshot. The raw publisher / downloadUrl never reach any sink.
        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(outdatedSnapshotRepository.count()).isEqualTo(beforeOutdated);
    }

    @Test
    void submitResultRejectsOutdatedSoftwareAsStringAndPersistsNothing() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-OSW-STR"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-osw-str", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        long beforeOutdated = outdatedSnapshotRepository.count();

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithOutdatedSoftware(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED,
                                "publisher=Acme; downloadUrl=http://acme.example")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.inventory.outdatedSoftware");

        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(outdatedSnapshotRepository.count()).isEqualTo(beforeOutdated);
    }

    @Test
    void submitResultRejectsTopLevelOutdatedSoftwareAsListAndPersistsNothing() {
        // The top-level alias (details.outdatedSoftware, no inventory wrapper)
        // some agent versions emit must fail-closed on the same non-Map type
        // confusion as the inventory-nested path.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-OSW-TOP"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-osw-top", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        long beforeOutdated = outdatedSnapshotRepository.count();

        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("outdatedSoftware", java.util.List.of(
                java.util.Map.of("packageId", "Acme.Tool",
                        "publisher", "Acme Corp")));

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        new AgentCommandResultRequest(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, "done", details,
                                null, null, 0,
                                Instant.now().minusSeconds(5), Instant.now())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("$.outdatedSoftware");

        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(outdatedSnapshotRepository.count()).isEqualTo(beforeOutdated);
    }

    @Test
    void submitResultRejectsCaseVariantPackageKeyAndPersistsNothing() {
        // P1: additionalProperties:false is exact + case-sensitive. A package
        // object carrying both the valid packageId and a case-variant extra
        // (PackageId — a redaction-gated field smuggled past a lowercase
        // allowlist) is rejected, not silently dropped.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-OSW-CASE"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-osw-case", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        java.util.Map<String, Object> pkg = new java.util.LinkedHashMap<>();
        pkg.put("packageId", "Acme.Tool");
        pkg.put("installedVersion", "1.0.0");
        pkg.put("availableVersion", "2.0.0");
        // Case-variant extras that a case-insensitive allowlist would drop.
        pkg.put("PackageId", "Acme.Tool.Display Name");
        pkg.put("Publisher", "Acme Corp");

        java.util.Map<String, Object> osBlock = validOutdatedSoftwareBlock();
        osBlock.put("upgradeCount", 1);
        osBlock.put("upgrade", java.util.List.of(pkg));

        long beforeOutdated = outdatedSnapshotRepository.count();

        assertThatThrownBy(() ->
                commandService.submitResult(
                        principal(device),
                        command.getId(),
                        resultRequestWithOutdatedSoftware(
                                claimed.claimId(), claimed.attemptNumber(),
                                CommandResultStatus.SUCCEEDED, osBlock)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("case-variant");

        assertThat(resultRepository.findByCommand_Id(command.getId())).isEmpty();
        assertThat(outdatedSnapshotRepository.count()).isEqualTo(beforeOutdated);
    }

    @Test
    void submitResultIngestsValidOutdatedSoftwareBlock() {
        // Positive control: a well-formed outdatedSoftware Map block still
        // sanitizes, persists, and ingests exactly one snapshot — proving the
        // fail-closed reject above did not break the happy path.
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.ONLINE, "PC-OSW-OK"));
        EndpointCommand command = commandRepository.saveAndFlush(command(device, "cmd-osw-ok", 10));
        AgentCommandResponse claimed = commandService.claimNext(principal(device)).orElseThrow();

        long beforeOutdated = outdatedSnapshotRepository.count();

        commandService.submitResult(
                principal(device),
                command.getId(),
                resultRequestWithOutdatedSoftware(
                        claimed.claimId(), claimed.attemptNumber(),
                        CommandResultStatus.SUCCEEDED, validOutdatedSoftwareBlock()));

        EndpointCommandResult result = resultRepository.findByCommand_Id(command.getId()).orElseThrow();
        assertThat(result.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(outdatedSnapshotRepository.count()).isEqualTo(beforeOutdated + 1);
    }

    /** A contract-valid outdated-software block (no upgrades). */
    private java.util.Map<String, Object> validOutdatedSoftwareBlock() {
        java.util.Map<String, Object> os = new java.util.LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 0);
        os.put("upgrade", java.util.List.of());
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 1200);
        return os;
    }

    /**
     * Build an AgentCommandResultRequest carrying an arbitrary
     * {@code outdatedSoftware} value under {@code details.inventory}. The
     * value type is intentionally {@code Object} so a test can wire a List /
     * String to exercise the non-Map type-confusion reject.
     */
    private AgentCommandResultRequest resultRequestWithOutdatedSoftware(
            String claimId, int attemptNumber, CommandResultStatus status,
            Object outdatedSoftware) {
        java.util.Map<String, Object> inventory = new java.util.LinkedHashMap<>();
        inventory.put("outdatedSoftware", outdatedSoftware);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
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

    private EndpointCommand installCommand(EndpointDevice device, String idempotencyKey, int priority) {
        EndpointCommand command = command(device, idempotencyKey, priority);
        command.setCommandType(CommandType.INSTALL_SOFTWARE);
        command.setPayload(Map.of(
                "catalogItemId", "7zip-stable",
                "packageId", "7zip.7zip",
                "provider", "WINGET"));
        return command;
    }

    private EndpointCommand activeInstallCommand(EndpointDevice device, String idempotencyKey, int priority) {
        EndpointCommand command = installCommand(device, idempotencyKey, priority);
        command.setStatus(CommandStatus.DELIVERED);
        command.setLockedBy("active-install-claim");
        command.setLockedUntil(Instant.now().plusSeconds(300));
        command.setAttemptCount(1);
        command.setDeliveredAt(Instant.now().minusSeconds(30));
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
