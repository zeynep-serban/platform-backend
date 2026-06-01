package com.example.endpointadmin.service;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDiagnosticsSnapshotRepository;
import com.example.endpointadmin.security.DiagnosticsPayloadPolicy;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — AG-038-be diagnostics ingest + query tests. Mirrors AG-037
 * {@code EndpointHotfixPostureServiceTest} H2 slice. Pins:
 *
 * <ul>
 *   <li>Canonical hash INCLUDES every persistable field per Codex iter-3 P1 #4 (lastPollLatencyMs + probeDurationMs
 *       included per iter-3 P1 #4) — same posture with different
 *       latency/duration appends new snapshot.</li>
 *   <li>Canonical hash INCLUDES lastError + probeErrors + configHash
 *       + agentVersion + reachability — change in any appends a NEW
 *       snapshot.</li>
 *   <li>Primary source_command_result_id idempotency + secondary
 *       canonical-hash dedupe.</li>
 *   <li>Strict policy enforcement at ingest (missing required key →
 *       IllegalArgumentException, no snapshot persisted).</li>
 * </ul>
 *
 * <p>Slice runs on H2. PG-specific atomicity + winner re-lookup race
 * coverage lives in the Testcontainers integration test (separate).
 */
@IsolatedH2DataJpaTest
@Import({EndpointDiagnosticsService.class, DiagnosticsPayloadPolicy.class})
class EndpointDiagnosticsServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointDiagnosticsService service;

    @Autowired
    private EndpointDiagnosticsSnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Test
    void ingestGoldenWindowsPersistsSnapshot() {
        EndpointDevice device = seedDevice("DEV-A");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        EndpointDiagnosticsSnapshot snap = service.ingest(
                device, command, result, wrap(golden()));

        assertThat(snap).isNotNull();
        assertThat(snap.getId()).isNotNull();
        assertThat(snap.getTenantId()).isEqualTo(TENANT_A);
        assertThat(snap.getDeviceId()).isEqualTo(device.getId());
        assertThat(snap.getSchemaVersion()).isEqualTo(1);
        assertThat(snap.getSupported()).isTrue();
        assertThat(snap.getProbeComplete()).isTrue();
        assertThat(snap.getAgentVersion()).isEqualTo("0.7.2");
        assertThat(snap.getConfigHash()).hasSize(64);
        assertThat(snap.getBackendDnsReachable()).isTrue();
        assertThat(snap.getBackendTlsValid()).isTrue();
        assertThat(snap.getLastErrorOccurredAt()).isNull();
        assertThat(snap.getLastErrorCode()).isNull();
        assertThat(snap.getLastErrorSummary()).isNull();
        assertThat(snap.getProbeErrors()).isEmpty();
        assertThat(snap.getPayloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void ingestUnsupportedPlatformPersistsAsEvidence() {
        EndpointDevice device = seedDevice("DEV-B");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("agentVersion", "unknown");
        p.put("configHash", "unknown");
        p.put("lastPollLatencyMs", 0);
        p.put("backendDNSReachable", false);
        p.put("backendTLSValid", false);
        p.put("probeDurationMs", 0);
        p.put("probeErrors", List.of(Map.of("code", "UNSUPPORTED_PLATFORM")));

        EndpointDiagnosticsSnapshot snap = service.ingest(
                device, command, result, wrap(p));

        assertThat(snap.getSupported()).isFalse();
        assertThat(snap.getProbeComplete()).isFalse();
        assertThat(snap.getProbeErrors()).hasSize(1);
        assertThat(snap.getProbeErrors().get(0).getCode()).isEqualTo("UNSUPPORTED_PLATFORM");
    }

    @Test
    void duplicateSourceCommandResultIdIdempotentNoOp() {
        EndpointDevice device = seedDevice("DEV-C");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        EndpointDiagnosticsSnapshot first = service.ingest(device, command, result, wrap(golden()));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command, result, wrap(golden()));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    @Test
    void differentLatencyAppendsNewSnapshot() {
        // Codex 019e82d7 iter-3 P1 #4 revise: latency now INCLUDED in
        // canonical hash so each fresh observation appends a new snapshot
        // and /latest reflects the most recent measured latency. Iter-2's
        // "exclude latency from hash" caused /latest staleness.
        EndpointDevice device = seedDevice("DEV-D");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        p1.put("lastPollLatencyMs", 100);
        Map<String, Object> p2 = golden();
        p2.put("lastPollLatencyMs", 9999);

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
        // /latest reflects the most recent measured latency.
        var latest = service.findLatest(TENANT_A, device.getId()).orElseThrow();
        assertThat(latest.getLastPollLatencyMs()).isEqualTo(9999);
    }

    @Test
    void differentProbeDurationAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("DEV-E");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        p1.put("probeDurationMs", 100);
        Map<String, Object> p2 = golden();
        p2.put("probeDurationMs", 9999);

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void identicalPayloadHashDedupesToSameSnapshot() {
        // Pure dedupe: same canonical state including same latency +
        // duration → same hash → no second row appended (idempotent
        // same-observation replay).
        EndpointDevice device = seedDevice("DEV-DEDUP");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(golden()));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(golden()));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    @Test
    void changedConfigHashAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("DEV-F");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        Map<String, Object> p2 = golden();
        p2.put("configHash", "0".repeat(64));

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void backendReachabilityChangeAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("DEV-G");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        Map<String, Object> p2 = golden();
        p2.put("backendDNSReachable", false);

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void lastErrorChangeAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("DEV-H");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        Map<String, Object> p2 = golden();
        p2.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "NEXT_COMMAND_TIMEOUT",
                "summary", "poll timeout"));

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getLastErrorCode()).isEqualTo("NEXT_COMMAND_TIMEOUT");
        assertThat(second.getLastErrorSummary()).isEqualTo("poll timeout");
    }

    @Test
    void probeErrorsListAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("DEV-I");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        Map<String, Object> p2 = golden();
        p2.put("probeErrors", List.of(Map.of("code", "DNS_TIMEOUT")));

        EndpointDiagnosticsSnapshot first = service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getProbeErrors()).hasSize(1);
    }

    @Test
    void missingRequiredKeyRejectedNoSnapshot() {
        EndpointDevice device = seedDevice("DEV-J");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        Map<String, Object> p = golden();
        p.remove("agentVersion");

        assertThatThrownBy(() -> service.ingest(device, command, result, wrap(p)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void findLatestReturnsMostRecent() {
        EndpointDevice device = seedDevice("DEV-K");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> p1 = golden();
        Map<String, Object> p2 = golden();
        p2.put("agentVersion", "0.7.3");

        service.ingest(device, command1, result1, wrap(p1));
        EndpointDiagnosticsSnapshot second = service.ingest(device, command2, result2, wrap(p2));

        var latest = service.findLatest(TENANT_A, device.getId());
        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).isEqualTo(second.getId());
        assertThat(latest.get().getAgentVersion()).isEqualTo("0.7.3");
    }

    @Test
    void findLatestEmptyWhenNoSnapshot() {
        var latest = service.findLatest(TENANT_A, UUID.randomUUID());
        assertThat(latest).isEmpty();
    }

    @Test
    void hasDiagnosticsBlockTrueWhenInventoryNested() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", Map.of("schemaVersion", 1));
        details.put("inventory", inventory);
        assertThat(EndpointDiagnosticsService.hasDiagnosticsBlock(details)).isTrue();
    }

    @Test
    void hasDiagnosticsBlockTrueWhenTopLevel() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("diagnostics", Map.of("schemaVersion", 1));
        assertThat(EndpointDiagnosticsService.hasDiagnosticsBlock(details)).isTrue();
    }

    @Test
    void hasDiagnosticsBlockFalseWhenAbsent() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("software", List.of()));
        assertThat(EndpointDiagnosticsService.hasDiagnosticsBlock(details)).isFalse();
    }

    @Test
    void hasDiagnosticsBlockFalseWhenNullDetails() {
        assertThat(EndpointDiagnosticsService.hasDiagnosticsBlock(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> golden() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", true);
        p.put("probeComplete", true);
        p.put("agentVersion", "0.7.2");
        p.put("configHash", "abc1234567890def".repeat(4));
        p.put("lastPollLatencyMs", 120);
        p.put("backendDNSReachable", true);
        p.put("backendTLSValid", true);
        p.put("probeDurationMs", 450);
        return p;
    }

    private Map<String, Object> wrap(Map<String, Object> diagnostics) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", diagnostics);
        details.put("inventory", inventory);
        return details;
    }

    private EndpointDevice seedDevice(String name) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_A);
        device.setHostname(name);
        device.setDisplayName(name);
        device.setStatus(DeviceStatus.ONLINE);
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("11");
        device.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand seedCommand(EndpointDevice device) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(TENANT_A);
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setStatus(CommandStatus.SUCCEEDED);
        command.setIssuedAt(Instant.now());
        command.setIdempotencyKey("test-" + UUID.randomUUID());
        command.setIssuedBySubject("test-subject");
        return commandRepository.saveAndFlush(command);
    }

    private EndpointCommandResult seedResult(EndpointDevice device, EndpointCommand command) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(TENANT_A);
        result.setCommand(command);
        result.setDevice(device);
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setReportedAt(Instant.now());
        return resultRepository.saveAndFlush(result);
    }
}
