package com.example.endpointadmin.service;

import com.example.endpointadmin.event.HardwareInventorySnapshotPersistedEvent;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHardwareInventoryDisk;
import com.example.endpointadmin.model.EndpointHardwareInventoryNetworkInterface;
import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHardwareInventorySnapshotRepository;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-022 — Hardware Inventory ingest + query tests (Faz 22.5).
 *
 * <p>Slice runs on H2; PG-specific composite-FK rejection + DB CHECK
 * coverage lives in
 * {@code EndpointHardwareInventoryPostgresIntegrationTest} (follow-up
 * commit).
 *
 * <p>Covers Codex {@code 019e7007} acceptance gates relevant to the
 * service layer:
 *
 * <ul>
 *   <li>First ingest persists snapshot + cascades child disk/NIC rows</li>
 *   <li>Idempotency probe: re-delivering the same {@code source_
 *       command_result_id} returns the existing row, no duplicate</li>
 *   <li>{@code saveAndFlush} happy-no-op probe (race-catch branch
 *       coverage lives in
 *       {@code EndpointHardwareInventoryPostgresIntegrationTest})</li>
 *   <li>{@link ApplicationEventPublisher} receives bounded metadata
 *       (no MAC / IP / domain / serial)</li>
 *   <li>{@code findLatest} ordering is deterministic
 *       ({@code collected_at DESC, created_at DESC, id DESC})</li>
 *   <li>{@code hasHardwareBlock} predicate gates the hook entry</li>
 * </ul>
 */
@IsolatedH2DataJpaTest
@Import({
        EndpointHardwareInventoryService.class,
        EndpointHardwareInventoryServiceTest.RecordingEventPublisherConfig.class
})
class EndpointHardwareInventoryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointHardwareInventoryService service;

    @Autowired
    private EndpointHardwareInventorySnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RecordingEventPublisher recordingEvents;

    // ------------------------------------------------------------------
    // hasHardwareBlock predicate
    // ------------------------------------------------------------------

    @Test
    void hasHardwareBlockTrueForInventoryHardwareSubtree() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("hardware", Map.of("schemaVersion", 1)));

        assertThat(EndpointHardwareInventoryService.hasHardwareBlock(details))
                .isTrue();
    }

    @Test
    void hasHardwareBlockTrueForTopLevelAlias() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hardware", Map.of("schemaVersion", 1));

        assertThat(EndpointHardwareInventoryService.hasHardwareBlock(details))
                .isTrue();
    }

    @Test
    void hasHardwareBlockFalseForSoftwareOnlyDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("software",
                Map.of("appCount", 0, "apps", List.of())));

        assertThat(EndpointHardwareInventoryService.hasHardwareBlock(details))
                .isFalse();
    }

    @Test
    void hasHardwareBlockFalseForNull() {
        assertThat(EndpointHardwareInventoryService.hasHardwareBlock(null))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // First ingest happy path
    // ------------------------------------------------------------------

    @Test
    void firstIngestPersistsSnapshotWithDisksAndInterfaces() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = effectiveDetailsWithHardware(
                hardwareWithDisksAndInterfaces());

        EndpointHardwareInventorySnapshot persisted =
                service.ingest(device, command, result, details);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getDeviceId()).isEqualTo(device.getId());
        assertThat(persisted.getTenantId()).isEqualTo(TENANT_A);
        assertThat(persisted.getSourceCommandResultId()).isEqualTo(result.getId());
        assertThat(persisted.getSchemaVersion()).isEqualTo(1);
        assertThat(persisted.getCpuModel()).isEqualTo("Intel i7-1260P");
        assertThat(persisted.getRamTotalBytes()).isEqualTo(34359738368L);
        assertThat(persisted.getDisks()).hasSize(2);
        assertThat(persisted.getNetworkInterfaces()).hasSize(1);
        assertThat(persisted.getPayloadHashSha256())
                .matches("[a-f0-9]{64}");

        // Bounded event metadata.
        assertThat(recordingEvents.captured()).hasSize(1);
        HardwareInventorySnapshotPersistedEvent event = recordingEvents.captured().get(0);
        assertThat(event.tenantId()).isEqualTo(TENANT_A);
        assertThat(event.deviceId()).isEqualTo(device.getId());
        assertThat(event.snapshotId()).isEqualTo(persisted.getId());
        assertThat(event.sourceCommandId()).isEqualTo(command.getId());
        assertThat(event.diskCount()).isEqualTo(2);
        assertThat(event.networkInterfaceCount()).isEqualTo(1);
        assertThat(event.supported()).isTrue();

        // Persisted row count matches.
        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    void reIngestWithSameCommandResultReturnsExistingSnapshot() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = effectiveDetailsWithHardware(
                hardwareWithDisksAndInterfaces());

        EndpointHardwareInventorySnapshot first =
                service.ingest(device, command, result, details);
        recordingEvents.clear();

        // Second ingest of the SAME command result must no-op.
        EndpointHardwareInventorySnapshot second =
                service.ingest(device, command, result, details);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        // No second event published — re-ingest is a probe hit.
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void reIngestIdenticalPayloadUnderDifferentCommandResultDeduplicates() {
        // BE-022Q payload-hash deep-equality dedupe (#327): the agent
        // re-collects BYTE-IDENTICAL hardware (same collectedAt → same
        // payload hash) under a DIFFERENT command-result, so the
        // source_command_result_id probe misses. The secondary
        // payload-hash probe must return the existing snapshot rather
        // than appending a duplicate row.
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        String sharedCollectedAt = collectedAtMinutesAgo(45);
        Map<String, Object> details1 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(sharedCollectedAt));
        Map<String, Object> details2 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(sharedCollectedAt));

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1);
        EndpointHardwareInventorySnapshot first =
                service.ingest(device, cmd1, result1, details1);
        recordingEvents.clear();

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2);
        EndpointHardwareInventorySnapshot second =
                service.ingest(device, cmd2, result2, details2);

        // Same row returned, no append, no second event.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void changedPayloadUnderDifferentCommandResultStillAppends() {
        // Dedupe must NOT swallow a genuine hardware change: a different
        // payload (different collectedAt → different hash) under a new
        // command-result appends a new snapshot (append-only history
        // invariant preserved alongside the dedupe probe).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        Map<String, Object> details1 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(collectedAtMinutesAgo(120)));
        Map<String, Object> details2 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(collectedAtMinutesAgo(30)));

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1);
        EndpointHardwareInventorySnapshot first =
                service.ingest(device, cmd1, result1, details1);

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2);
        EndpointHardwareInventorySnapshot second =
                service.ingest(device, cmd2, result2, details2);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void distinctCommandResultsProduceAppendOnlyHistory() {
        // Append-only history: two different command-results on the
        // same device → two snapshots (no UNIQUE on (tenant, device)).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1);
        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2);

        // Distinct collectedAt so the latest ordering is stable —
        // details1 strictly older than details2 (Codex iter-2 must-fix
        // #2: deterministic dynamic timestamps).
        Map<String, Object> details1 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(collectedAtMinutesAgo(120)));
        Map<String, Object> details2 = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(collectedAtMinutesAgo(30)));

        EndpointHardwareInventorySnapshot first =
                service.ingest(device, cmd1, result1, details1);
        EndpointHardwareInventorySnapshot second =
                service.ingest(device, cmd2, result2, details2);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);

        Optional<EndpointHardwareInventorySnapshot> latest =
                service.findLatest(TENANT_A, device.getId());
        assertThat(latest).isPresent();
        // Latest is the more recent collected_at (11:00 > 10:00).
        assertThat(latest.get().getId()).isEqualTo(second.getId());
    }

    // ------------------------------------------------------------------
    // Query — deterministic ordering
    // ------------------------------------------------------------------

    @Test
    void findLatestReturnsEmptyForNoSnapshots() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        Optional<EndpointHardwareInventorySnapshot> latest =
                service.findLatest(TENANT_A, device.getId());

        assertThat(latest).isEmpty();
    }

    // ------------------------------------------------------------------
    // collectedAt sanity range (Codex 019e7007 iter-2 must-fix #2:
    // dedicated reject tests for too-old / too-far-future timestamps).
    // ------------------------------------------------------------------

    @Test
    void rejectsCollectedAtTooFarInPast() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        // 91 days old — outside the service's 90-day past sanity bound.
        Map<String, Object> details = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(collectedAtMinutesAgo(91L * 24 * 60)));

        assertThatThrownBy(() ->
                service.ingest(device, command, result, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too far in the past");
    }

    @Test
    void rejectsCollectedAtTooFarInFuture() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        // 2 hours into the future — outside the +1 hour future bound.
        Map<String, Object> details = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(
                        Instant.now().plus(Duration.ofHours(2)).toString()));

        assertThatThrownBy(() ->
                service.ingest(device, command, result, details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too far in the future");
    }

    // ------------------------------------------------------------------
    // redacted_payload probeErrors substitution (Codex 019e7007 iter-2
    // must-fix #3: prove that raw probeErrors fields do not leak into
    // redacted_payload via the bounded {code, summary<=256} projection).
    // ------------------------------------------------------------------

    @Test
    void redactedPayloadSubstitutesBoundedProbeErrors() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        // Build a hardware sub-tree with raw probeErrors carrying
        // extra fields the service must NOT propagate to
        // redactedPayload.probeErrors. Sanitizer would normally
        // strip user-path patterns from the summary; here we use a
        // benign summary + extra `stackTrace` to test the bounded
        // projection.
        Map<String, Object> hw = baseHardware();
        hw.put("collectedAt", collectedAtMinutesAgo(15));
        List<Map<String, Object>> probeErrors = new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", "PROBE_TIMEOUT");
        entry.put("summary", "winget probe timed out after 5s");
        // Raw stackTrace + extras — must be dropped.
        entry.put("stackTrace", "at com.example.Probe.run(Probe.java:42)");
        entry.put("rawCommandOutput", "stderr blob");
        probeErrors.add(entry);
        hw.put("probeErrors", probeErrors);

        Map<String, Object> details = effectiveDetailsWithHardware(hw);
        EndpointHardwareInventorySnapshot persisted =
                service.ingest(device, command, result, details);

        // Snapshot probe_errors (entity scalar) bounded:
        assertThat(persisted.getProbeErrors()).hasSize(1);
        Map<String, Object> first = persisted.getProbeErrors().get(0);
        assertThat(first).containsOnlyKeys("code", "summary");
        assertThat(first.get("code")).isEqualTo("PROBE_TIMEOUT");
        assertThat(first.get("summary"))
                .isEqualTo("winget probe timed out after 5s");

        // redactedPayload.probeErrors also bounded — substitution
        // (Codex iter-1 must-fix #2).
        Object redactedProbeErrors = persisted.getRedactedPayload().get("probeErrors");
        assertThat(redactedProbeErrors).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redactedList = (List<Map<String, Object>>) redactedProbeErrors;
        assertThat(redactedList).hasSize(1);
        assertThat(redactedList.get(0))
                .containsOnlyKeys("code", "summary");
        // Raw fields explicitly NOT present in redactedPayload.
        assertThat(redactedList.get(0)).doesNotContainKeys(
                "stackTrace", "rawCommandOutput");
    }

    @Test
    void redactedPayloadProbeErrorsSummaryTruncatedTo256Chars() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        // 300-char benign summary — bounded projection must truncate
        // to 256.
        String longSummary = "x".repeat(300);
        Map<String, Object> hw = baseHardware();
        hw.put("collectedAt", collectedAtMinutesAgo(15));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", "PROBE_VERBOSE");
        entry.put("summary", longSummary);
        hw.put("probeErrors", List.of(entry));

        Map<String, Object> details = effectiveDetailsWithHardware(hw);
        EndpointHardwareInventorySnapshot persisted =
                service.ingest(device, command, result, details);

        String storedSummary = (String) persisted.getProbeErrors().get(0).get("summary");
        assertThat(storedSummary).hasSize(256);
    }

    @Test
    void findLatestUsesCollectedAtDescTiebreak() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        // Three snapshots with strictly distinct collectedAt — dynamic
        // offsets stay within the service's 90-day past sanity bound
        // (Codex iter-3 must-fix: remove residual hard-coded dates).
        EndpointHardwareInventorySnapshot s1 = ingestAtTimestamp(
                device, collectedAtMinutesAgo(48 * 60)); // 48h ago
        EndpointHardwareInventorySnapshot s2 = ingestAtTimestamp(
                device, collectedAtMinutesAgo(60));      // 1h ago — newest
        EndpointHardwareInventorySnapshot s3 = ingestAtTimestamp(
                device, collectedAtMinutesAgo(36 * 60)); // 36h ago

        Optional<EndpointHardwareInventorySnapshot> latest =
                service.findLatest(TENANT_A, device.getId());

        assertThat(latest).isPresent();
        // Latest collected_at = 2026-05-28T10:00:00Z (s2).
        assertThat(latest.get().getId()).isEqualTo(s2.getId());
    }

    // ------------------------------------------------------------------
    // Fixtures + helpers
    // ------------------------------------------------------------------

    /**
     * Build an ISO-8601 timestamp at {@code minutesAgo} minutes before
     * {@code Instant.now()}. Used to keep test fixtures within the
     * service's 90-day past sanity bound (Codex 019e7007 iter-1
     * must-fix #4) without baking a hard-coded date that would expire.
     */
    private static String collectedAtMinutesAgo(long minutesAgo) {
        return Instant.now().minus(Duration.ofMinutes(minutesAgo)).toString();
    }

    private EndpointHardwareInventorySnapshot ingestAtTimestamp(
            EndpointDevice device, String iso) {
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);
        Map<String, Object> details = effectiveDetailsWithHardware(
                hardwareWithCollectedAt(iso));
        return service.ingest(device, command, result, details);
    }

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname);
        device.setStatus(DeviceStatus.ONLINE);
        device.setOsType(OsType.WINDOWS);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand persistCommand(EndpointDevice device) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(device.getTenantId());
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setStatus(CommandStatus.SUCCEEDED);
        command.setIdempotencyKey("test-cmd-" + UUID.randomUUID());
        command.setIssuedAt(Instant.now());
        command.setIssuedBySubject("test-admin@example.com");
        command.setMaxAttempts(3);
        return commandRepository.saveAndFlush(command);
    }

    private EndpointCommandResult persistResult(EndpointCommand command) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setReportedAt(Instant.now());
        result.setResultPayload(new LinkedHashMap<>());
        return resultRepository.saveAndFlush(result);
    }

    /** Build an effectiveDetails map (sanitized; caller is assumed
     * to have run HardwareInventoryPayloadPolicy.sanitize). */
    private Map<String, Object> effectiveDetailsWithHardware(Map<String, Object> hardware) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", hardware);
        details.put("inventory", inventory);
        return details;
    }

    private Map<String, Object> hardwareWithCollectedAt(String iso) {
        Map<String, Object> hw = baseHardware();
        hw.put("collectedAt", iso);
        return hw;
    }

    private Map<String, Object> hardwareWithDisksAndInterfaces() {
        Map<String, Object> hw = baseHardware();
        hw.put("collectedAt", collectedAtMinutesAgo(60));

        List<Map<String, Object>> disks = new ArrayList<>();
        Map<String, Object> disk1 = new LinkedHashMap<>();
        disk1.put("devicePath", "/dev/sda");
        disk1.put("model", "Samsung 980 Pro");
        disk1.put("mediaType", "NVME");
        disk1.put("capacityBytes", 1000000000000L);
        disk1.put("freeBytes", 500000000000L);
        disk1.put("busType", "NVME");
        disk1.put("isRemovable", false);
        disks.add(disk1);
        Map<String, Object> disk2 = new LinkedHashMap<>();
        disk2.put("devicePath", "/dev/sdb");
        disk2.put("model", "Crucial MX500");
        disk2.put("mediaType", "SSD");
        disk2.put("capacityBytes", 500000000000L);
        disk2.put("freeBytes", 250000000000L);
        disks.add(disk2);
        hw.put("disks", disks);

        List<Map<String, Object>> interfaces = new ArrayList<>();
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("name", "Ethernet 1");
        nic.put("macAddress", "aa:bb:cc:dd:ee:ff");
        nic.put("interfaceType", "ETHERNET");
        nic.put("linkState", "UP");
        nic.put("ipAddresses", List.of("192.0.2.10", "fe80::1"));
        interfaces.add(nic);
        hw.put("networkInterfaces", interfaces);

        return hw;
    }

    private Map<String, Object> baseHardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("schemaVersion", 1);
        hw.put("supported", true);
        hw.put("cpuModel", "Intel i7-1260P");
        hw.put("cpuCores", 12);
        hw.put("cpuFrequencyMhz", 2400);
        hw.put("ramTotalBytes", 34359738368L);
        hw.put("ramAvailableBytes", 12000000000L);
        hw.put("osName", "Windows 11");
        hw.put("osVersion", "10.0.22631");
        hw.put("osArch", "x64");
        hw.put("biosVendor", "Phoenix");
        hw.put("biosVersion", "1.0.4");
        hw.put("manufacturer", "Dell");
        hw.put("systemModel", "Latitude 7430");
        hw.put("domainJoined", false);
        return hw;
    }

    // ------------------------------------------------------------------
    // Recording event listener — captures HardwareInventorySnapshot
    // PersistedEvent via Spring's @EventListener so we don't need to
    // override the ApplicationEventPublisher bean (which Spring owns).
    // ------------------------------------------------------------------

    @TestConfiguration
    static class RecordingEventPublisherConfig {
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    static class RecordingEventPublisher {
        private final List<HardwareInventorySnapshotPersistedEvent> events = new ArrayList<>();

        @EventListener
        public void onHardwareInventorySnapshotPersisted(
                HardwareInventorySnapshotPersistedEvent event) {
            events.add(event);
        }

        List<HardwareInventorySnapshotPersistedEvent> captured() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
