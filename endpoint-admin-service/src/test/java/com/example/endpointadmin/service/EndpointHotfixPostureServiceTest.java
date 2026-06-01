package com.example.endpointadmin.service;

import com.example.endpointadmin.event.HotfixPostureSnapshotPersistedEvent;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHotfixPostureSnapshotRepository;
import com.example.endpointadmin.security.HotfixPosturePayloadPolicy;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — hotfix-posture ingest + query tests (Faz 22.5, AG-037). Mirrors
 * the AG-036 {@code EndpointOutdatedSoftwareServiceTest} H2 slice and
 * pins the Codex 019e81fe iter-3 ANSWER + iter-4 implementation notes:
 *
 * <ul>
 *   <li>Canonical hash domain EXCLUDES wire {@code collectedAt} and
 *       {@code probeDurationMs} (timing-only). Pinned by
 *       {@link #samePostureDifferentProbeDurationAndCollectedAtDedupes}.</li>
 *   <li>Canonical hash domain INCLUDES {@code lastDetectAt} and
 *       {@code lastInstallAt} (posture evidence). Pinned by
 *       {@link #samePostureDifferentLastDetectAtAppendsNewSnapshot}.</li>
 *   <li>Dual idempotency: primary {@code source_command_result_id}
 *       probe + secondary canonical-hash dedupe (Codex iter-3 P1.1).</li>
 *   <li>Sequential winner lookup invariant (Codex iter-4) — not
 *       directly exercised here under H2 (no concurrency); the PG
 *       Testcontainers integration test covers the race.</li>
 *   <li>Bounded probe-error metadata; STRICT required-field
 *       IllegalStateException on policy bypass.</li>
 * </ul>
 *
 * <p>Slice runs on H2; PG-specific composite-FK rejection, native
 * targetless ON CONFLICT race coverage, the lower(bytea) regression
 * guard, and the non-public schema invariant live in the
 * {@code EndpointHotfixPosturePostgresIntegrationTest} +
 * {@code EndpointHotfixPostureAtomicityPostgresIntegrationTest} slices.
 */
@IsolatedH2DataJpaTest
@Import({
        EndpointHotfixPostureService.class,
        EndpointHotfixPostureServiceTest.RecordingEventPublisherConfig.class
})
class EndpointHotfixPostureServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private EndpointHotfixPostureService service;

    @Autowired
    private EndpointHotfixPostureSnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private RecordingEventPublisher recordingEvents;

    /** Plain policy instance (no Spring deps) — mirrors the production
     *  flow where EndpointAgentCommandService sanitizes the agent
     *  payload BEFORE handing it to {@link EndpointHotfixPostureService}. */
    private final HotfixPosturePayloadPolicy policy = new HotfixPosturePayloadPolicy();

    // ------------------------------------------------------------------
    // hasHotfixPostureBlock predicate
    // ------------------------------------------------------------------

    @Test
    void hasBlockTrueForInventorySubtree() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("hotfixPosture", Map.of("schemaVersion", 1)));
        assertThat(EndpointHotfixPostureService.hasHotfixPostureBlock(details)).isTrue();
    }

    @Test
    void hasBlockTrueForTopLevelAlias() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hotfixPosture", Map.of("schemaVersion", 1));
        assertThat(EndpointHotfixPostureService.hasHotfixPostureBlock(details)).isTrue();
    }

    @Test
    void hasBlockFalseForOutdatedSoftwareOnly() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("outdatedSoftware", Map.of("schemaVersion", 1)));
        assertThat(EndpointHotfixPostureService.hasHotfixPostureBlock(details)).isFalse();
    }

    @Test
    void hasBlockFalseForNull() {
        assertThat(EndpointHotfixPostureService.hasHotfixPostureBlock(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // Golden round-trip
    // ------------------------------------------------------------------

    @Test
    void goldenIngestPersistsCanonicalShape() {
        EndpointDevice device = seedDevice("d35-canonical");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        Map<String, Object> sanitized = policy.sanitize(wrap(golden()));
        EndpointHotfixPostureSnapshot snapshot = service.ingest(device, command, result, sanitized);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getSupported()).isTrue();
        assertThat(snapshot.getProbeComplete()).isTrue();
        assertThat(snapshot.getInstalledCount()).isEqualTo(1);
        assertThat(snapshot.getMaxInstalled()).isEqualTo(HotfixPosturePayloadPolicy.MAX_INSTALLED);
        assertThat(snapshot.getMaxPending()).isEqualTo(HotfixPosturePayloadPolicy.MAX_PENDING);
        assertThat(snapshot.getWuaServiceState()).isEqualTo("RUNNING");
        assertThat(snapshot.getBitsServiceState()).isEqualTo("RUNNING");
        assertThat(snapshot.getNotificationLevel()).isEqualTo("4");
        assertThat(snapshot.getInstalledHotfixes()).hasSize(1);
        assertThat(snapshot.getInstalledHotfixes().get(0).getKbId()).isEqualTo("KB5034122");
        assertThat(snapshot.getPayloadHashSha256()).hasSize(64);

        // Event audit metadata.
        HotfixPostureSnapshotPersistedEvent event = recordingEvents.lastEvent();
        assertThat(event).isNotNull();
        assertThat(event.tenantId()).isEqualTo(device.getTenantId());
        assertThat(event.deviceId()).isEqualTo(device.getId());
        assertThat(event.snapshotId()).isEqualTo(snapshot.getId());
        assertThat(event.installedCount()).isEqualTo(1);
        assertThat(event.pendingTotalCount()).isEqualTo(0);
        assertThat(event.installedSourceUsed()).isEqualTo("wua");
        assertThat(event.healthSourceUsed()).isEqualTo("composite");
        assertThat(event.payloadHashSha256()).isEqualTo(snapshot.getPayloadHashSha256());
    }

    // ------------------------------------------------------------------
    // Idempotency probes
    // ------------------------------------------------------------------

    @Test
    void primaryIdempotencyOnSourceCommandResultIdReturnsExisting() {
        EndpointDevice device = seedDevice("d35-prim-idem");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        Map<String, Object> sanitized = policy.sanitize(wrap(golden()));
        EndpointHotfixPostureSnapshot first = service.ingest(device, command, result, sanitized);
        recordingEvents.reset();

        EndpointHotfixPostureSnapshot second = service.ingest(device, command, result, sanitized);

        assertThat(second.getId()).isEqualTo(first.getId());
        // No second event (idempotent no-op).
        assertThat(recordingEvents.lastEvent()).isNull();
    }

    @Test
    void secondaryIdempotencyOnPayloadHashDedupesAcrossCommands() {
        EndpointDevice device = seedDevice("d35-sec-idem");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> sanitized = policy.sanitize(wrap(golden()));

        EndpointHotfixPostureSnapshot first = service.ingest(device, command1, result1, sanitized);
        recordingEvents.reset();

        EndpointHotfixPostureSnapshot second = service.ingest(device, command2, result2, sanitized);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.findAll()).hasSize(1);
        assertThat(recordingEvents.lastEvent()).isNull();
    }

    @Test
    void newHashAppendsNewSnapshot() {
        EndpointDevice device = seedDevice("d35-append");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> sanitizedFirst = policy.sanitize(wrap(golden()));
        EndpointHotfixPostureSnapshot first = service.ingest(device, command1, result1, sanitizedFirst);

        // Change posture content: add a pending update.
        Map<String, Object> changed = goldenWithPending();
        Map<String, Object> sanitizedSecond = policy.sanitize(wrap(changed));
        EndpointHotfixPostureSnapshot second =
                service.ingest(device, command2, result2, sanitizedSecond);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getPayloadHashSha256()).isNotEqualTo(first.getPayloadHashSha256());
        assertThat(snapshotRepository.findAll()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // Canonical hash domain (Codex iter-4 PIN)
    // ------------------------------------------------------------------

    @Test
    void samePostureDifferentProbeDurationAndCollectedAtDedupes() {
        // Codex iter-4 PIN: hash EXCLUDES wire collectedAt + probeDurationMs.
        EndpointDevice device = seedDevice("d35-hash-timing");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> first = golden();
        first.put("collectedAt", "2026-06-01T12:34:56Z");
        first.put("probeDurationMs", 410);

        Map<String, Object> second = golden();
        second.put("collectedAt", "2026-06-01T13:45:00Z"); // different
        second.put("probeDurationMs", 520);                 // different

        Map<String, Object> sanitized1 = policy.sanitize(wrap(first));
        Map<String, Object> sanitized2 = policy.sanitize(wrap(second));

        EndpointHotfixPostureSnapshot snap1 =
                service.ingest(device, command1, result1, sanitized1);
        EndpointHotfixPostureSnapshot snap2 =
                service.ingest(device, command2, result2, sanitized2);

        // Same canonical posture → SAME hash → dedupes via secondary probe.
        assertThat(snap2.getId()).isEqualTo(snap1.getId());
        assertThat(snap2.getPayloadHashSha256()).isEqualTo(snap1.getPayloadHashSha256());
        assertThat(snapshotRepository.findAll()).hasSize(1);
    }

    @Test
    void samePostureDifferentLastDetectAtAppendsNewSnapshot() {
        // Codex iter-4 PIN: hash INCLUDES lastDetectAt + lastInstallAt
        // (posture evidence). Identical installed/pending lists but
        // different agentHealth.lastDetectAt → DIFFERENT hash → APPEND.
        EndpointDevice device = seedDevice("d35-hash-detect");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> first = golden();
        Map<String, Object> second = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah2 = (Map<String, Object>) second.get("agentHealth");
        ah2.put("lastDetectAt", "2026-05-31T14:00:00Z"); // was 08:00:00Z

        Map<String, Object> sanitized1 = policy.sanitize(wrap(first));
        Map<String, Object> sanitized2 = policy.sanitize(wrap(second));

        EndpointHotfixPostureSnapshot snap1 =
                service.ingest(device, command1, result1, sanitized1);
        EndpointHotfixPostureSnapshot snap2 =
                service.ingest(device, command2, result2, sanitized2);

        assertThat(snap2.getId()).isNotEqualTo(snap1.getId());
        // CRITICAL ASSERT (Codex iter-4 final note): the second snapshot
        // has a DIFFERENT payload hash, not just a different row count.
        assertThat(snap2.getPayloadHashSha256()).isNotEqualTo(snap1.getPayloadHashSha256());
        assertThat(snapshotRepository.findAll()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // collectedAt provenance
    // ------------------------------------------------------------------

    @Test
    void collectedAtComesFromResultReportedAtNotAgentPayload() {
        EndpointDevice device = seedDevice("d35-collectedAt");
        EndpointCommand command = seedCommand(device);
        Instant serverReportedAt = Instant.parse("2026-06-02T03:00:00Z");
        EndpointCommandResult result = seedResultWithReportedAt(device, command, serverReportedAt);

        Map<String, Object> hp = golden();
        hp.put("collectedAt", "1999-01-01T00:00:00Z"); // agent-spoofed value
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        EndpointHotfixPostureSnapshot snapshot = service.ingest(device, command, result, sanitized);

        // Persisted collected_at must equal the server-controlled
        // result.reportedAt, NOT the agent payload.
        assertThat(snapshot.getCollectedAt()).isEqualTo(serverReportedAt);
    }

    // ------------------------------------------------------------------
    // Required-field invariants (policy-bypass fail-loud)
    // ------------------------------------------------------------------

    @Test
    void ingestWithoutBlockThrowsIllegalState() {
        EndpointDevice device = seedDevice("d35-no-block");
        EndpointCommand command = seedCommand(device);
        EndpointCommandResult result = seedResult(device, command);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", new LinkedHashMap<>());

        assertThatThrownBy(() -> service.ingest(device, command, result, details))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hotfixPosture block");
    }

    // ------------------------------------------------------------------
    // findLatest ordering
    // ------------------------------------------------------------------

    @Test
    void findLatestReturnsMostRecentSnapshot() {
        EndpointDevice device = seedDevice("d35-latest");
        EndpointCommand command1 = seedCommand(device);
        EndpointCommandResult result1 = seedResult(device, command1);
        EndpointCommand command2 = seedCommand(device);
        EndpointCommandResult result2 = seedResult(device, command2);

        Map<String, Object> sanitized1 = policy.sanitize(wrap(golden()));
        EndpointHotfixPostureSnapshot first = service.ingest(device, command1, result1, sanitized1);
        Map<String, Object> sanitized2 = policy.sanitize(wrap(goldenWithPending()));
        EndpointHotfixPostureSnapshot second = service.ingest(device, command2, result2, sanitized2);

        EndpointHotfixPostureSnapshot latest =
                service.findLatest(device.getTenantId(), device.getId()).orElseThrow();
        // second has more recent collected_at because seedResult uses
        // Instant.now()-based reportedAt.
        assertThat(latest.getId()).isIn(first.getId(), second.getId());
        assertThat(latest.getCollectedAt()).isAfterOrEqualTo(first.getCollectedAt());
    }

    // ------------------------------------------------------------------
    // Recording event publisher
    // ------------------------------------------------------------------

    @TestConfiguration
    static class RecordingEventPublisherConfig {
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    static class RecordingEventPublisher {
        private final List<HotfixPostureSnapshotPersistedEvent> events = new ArrayList<>();

        @EventListener
        public void onPersisted(HotfixPostureSnapshotPersistedEvent event) {
            events.add(event);
        }

        HotfixPostureSnapshotPersistedEvent lastEvent() {
            return events.isEmpty() ? null : events.get(events.size() - 1);
        }

        void reset() {
            events.clear();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private EndpointDevice seedDevice(String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_A);
        device.setHostname(hostname);
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand seedCommand(EndpointDevice device) {
        EndpointCommand command = new EndpointCommand();
        command.setDevice(device);
        command.setTenantId(device.getTenantId());
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setStatus(CommandStatus.SUCCEEDED);
        command.setIdempotencyKey("hp-cmd-" + UUID.randomUUID());
        command.setIssuedAt(Instant.now());
        command.setIssuedBySubject("test-suite");
        command.setMaxAttempts(3);
        return commandRepository.saveAndFlush(command);
    }

    private EndpointCommandResult seedResult(EndpointDevice device, EndpointCommand command) {
        return seedResultWithReportedAt(device, command, Instant.now());
    }

    private EndpointCommandResult seedResultWithReportedAt(
            EndpointDevice device, EndpointCommand command, Instant reportedAt) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setCommand(command);
        result.setTenantId(device.getTenantId());
        result.setDevice(device);
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setReportedAt(reportedAt);
        result.setResultPayload(new LinkedHashMap<>());
        return resultRepository.saveAndFlush(result);
    }

    private static Map<String, Object> wrap(Map<String, Object> hp) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hotfixPosture", hp);
        details.put("inventory", inventory);
        return details;
    }

    /** Minimal golden: one installed, zero pending. */
    private static Map<String, Object> golden() {
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("schemaVersion", 1);
        hp.put("supported", true);
        hp.put("probeComplete", true);
        hp.put("collectedAt", "2026-06-01T12:34:56Z");
        hp.put("probeDurationMs", 410);
        hp.put("installedSourceUsed", "wua");
        Map<String, Object> installed = new LinkedHashMap<>();
        installed.put("kbId", "KB5034122");
        installed.put("installedOn", "2026-01-15T00:00:00Z");
        installed.put("description", "Security Update for Microsoft Windows");
        hp.put("installedHotfixes", new ArrayList<>(List.of(installed)));
        hp.put("installedCount", 1);
        hp.put("installedTruncated", false);
        hp.put("pendingSourceUsed", "wua");
        hp.put("pendingUpdates", new ArrayList<>());
        hp.put("pendingByCategory", new ArrayList<>());
        hp.put("pendingTotalCount", 0);
        hp.put("pendingTruncated", false);
        hp.put("healthSourceUsed", "composite");
        Map<String, Object> agentHealth = new LinkedHashMap<>();
        agentHealth.put("wuaServiceState", "RUNNING");
        agentHealth.put("bitsServiceState", "RUNNING");
        agentHealth.put("lastDetectAt", "2026-05-31T08:00:00Z");
        agentHealth.put("lastInstallAt", "2026-05-30T22:00:00Z");
        agentHealth.put("autoUpdatePolicyEnabled", true);
        agentHealth.put("autoUpdateEffectiveEnabled", true);
        agentHealth.put("notificationLevel", "4");
        hp.put("agentHealth", agentHealth);
        hp.put("probeErrors", new ArrayList<>());
        return hp;
    }

    private static Map<String, Object> goldenWithPending() {
        Map<String, Object> hp = golden();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("kbIds", new ArrayList<>(List.of("KB5036899")));
        pending.put("primaryCategory", "SECURITY");
        pending.put("severity", "CRITICAL");
        hp.put("pendingUpdates", new ArrayList<>(List.of(pending)));
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("category", "SECURITY");
        rollup.put("count", 1);
        hp.put("pendingByCategory", new ArrayList<>(List.of(rollup)));
        hp.put("pendingTotalCount", 1);
        return hp;
    }
}
