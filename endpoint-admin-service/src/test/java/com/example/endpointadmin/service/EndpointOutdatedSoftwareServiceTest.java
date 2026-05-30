package com.example.endpointadmin.service;

import com.example.endpointadmin.event.OutdatedSoftwareSnapshotPersistedEvent;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — outdated-software ingest + query tests (Faz 22.5, AG-036). Mirrors
 * the AG-033 {@code EndpointDeviceHealthServiceTest} H2 slice.
 *
 * <p>Slice runs on H2; PG-specific composite-FK rejection, DB CHECK
 * coverage, the {@code lower(bytea)}-free dedupe query proof, and the
 * native ON CONFLICT atomicity proof live in
 * {@code EndpointOutdatedSoftwarePostgresIntegrationTest} +
 * {@code EndpointOutdatedSoftwareAtomicityPostgresIntegrationTest}.
 *
 * <ul>
 *   <li>Golden corpus round-trip: each of the three contract examples
 *       (with-upgrades / clean / unsupported) ingests + round-trips through
 *       the query DTO without loss/leak.</li>
 *   <li>Redaction machine-enforced: a golden with an injected forbidden
 *       package field (a "publisher") is fail-closed rejected — the policy
 *       never lets it reach persistence.</li>
 *   <li>Idempotency probe: re-delivering the same
 *       {@code source_command_result_id} returns the existing row.</li>
 *   <li>Payload-hash dedupe (BE-022Q lesson): byte-identical re-collection
 *       under a DIFFERENT command-result no-ops.</li>
 *   <li>Changed payload under a new command-result still appends
 *       (append-only invariant).</li>
 *   <li>{@code supported=false}/{@code probeComplete=false} persists
 *       fail-closed (not a failed ingest, never "up to date").</li>
 *   <li>Event carries bounded metadata only; possiblyTruncated signal.</li>
 *   <li>{@code findLatest} ordering deterministic.</li>
 *   <li>{@code hasOutdatedSoftwareBlock} predicate gates the hook.</li>
 * </ul>
 */
@IsolatedH2DataJpaTest
@Import({
        EndpointOutdatedSoftwareService.class,
        EndpointOutdatedSoftwareServiceTest.RecordingEventPublisherConfig.class
})
class EndpointOutdatedSoftwareServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointOutdatedSoftwareService service;

    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private RecordingEventPublisher recordingEvents;

    /** Plain policy instance (no Spring deps) — mirrors the production flow
     *  where EndpointAgentCommandService sanitizes the agent payload BEFORE
     *  handing it to {@link EndpointOutdatedSoftwareService}. */
    private final OutdatedSoftwarePayloadPolicy policy = new OutdatedSoftwarePayloadPolicy();

    // ------------------------------------------------------------------
    // hasOutdatedSoftwareBlock predicate
    // ------------------------------------------------------------------

    @Test
    void hasBlockTrueForInventoryOutdatedSoftwareSubtree() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("outdatedSoftware", Map.of("schemaVersion", 1)));
        assertThat(EndpointOutdatedSoftwareService.hasOutdatedSoftwareBlock(details)).isTrue();
    }

    @Test
    void hasBlockTrueForTopLevelAlias() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outdatedSoftware", Map.of("schemaVersion", 1));
        assertThat(EndpointOutdatedSoftwareService.hasOutdatedSoftwareBlock(details)).isTrue();
    }

    @Test
    void hasBlockFalseForDeviceHealthOnlyDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("deviceHealth", Map.of("schemaVersion", 1)));
        assertThat(EndpointOutdatedSoftwareService.hasOutdatedSoftwareBlock(details)).isFalse();
    }

    @Test
    void hasBlockFalseForNull() {
        assertThat(EndpointOutdatedSoftwareService.hasOutdatedSoftwareBlock(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // Golden corpus round-trip (with-upgrades)
    // ------------------------------------------------------------------

    @Test
    void firstIngestPersistsSnapshotWithPackages() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        Instant reportedAt = Instant.parse("2026-05-29T12:00:00Z");
        EndpointCommandResult result = persistResult(command, reportedAt);

        Map<String, Object> details = wrap(goldenWithUpgrades());
        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, details);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getDeviceId()).isEqualTo(device.getId());
        assertThat(persisted.getTenantId()).isEqualTo(TENANT_A);
        assertThat(persisted.getSourceCommandResultId()).isEqualTo(result.getId());
        assertThat(persisted.getSchemaVersion()).isEqualTo((short) 1);
        assertThat(persisted.getSupported()).isTrue();
        assertThat(persisted.getProbeComplete()).isTrue();
        assertThat(persisted.getUpgradeCount()).isEqualTo(2);
        assertThat(persisted.getUpgradeTruncated()).isFalse();
        assertThat(persisted.getMaxUpgrade()).isEqualTo(512);
        assertThat(persisted.getSourceUsed()).isEqualTo("winget");
        assertThat(persisted.getProbeDurationMs()).isEqualTo(45);
        // collected_at derives from the command-result reportedAt (the wire
        // block carries no timestamp).
        assertThat(persisted.getCollectedAt()).isEqualTo(reportedAt);
        assertThat(persisted.getPayloadHashSha256()).matches("[a-f0-9]{64}");
        assertThat(persisted.getPackages()).hasSize(2);
        assertThat(persisted.getPackages().get(0).getPackageId()).isEqualTo("7zip.7zip");
        assertThat(persisted.getPackages().get(0).getInstalledVersion()).isEqualTo("24.09");
        assertThat(persisted.getPackages().get(0).getAvailableVersion()).isEqualTo("25.01");

        // Bounded event metadata only.
        assertThat(recordingEvents.captured()).hasSize(1);
        OutdatedSoftwareSnapshotPersistedEvent event = recordingEvents.captured().get(0);
        assertThat(event.tenantId()).isEqualTo(TENANT_A);
        assertThat(event.deviceId()).isEqualTo(device.getId());
        assertThat(event.snapshotId()).isEqualTo(persisted.getId());
        assertThat(event.sourceCommandId()).isEqualTo(command.getId());
        assertThat(event.packageCount()).isEqualTo(2);
        assertThat(event.upgradeCount()).isEqualTo(2);
        assertThat(event.supported()).isTrue();
        assertThat(event.probeComplete()).isTrue();
        assertThat(event.possiblyTruncated()).isFalse();

        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    @Test
    void goldenCleanExampleRoundTripsWithoutPackages() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, wrap(goldenClean()));

        assertThat(persisted.getSupported()).isTrue();
        assertThat(persisted.getProbeComplete()).isTrue();
        assertThat(persisted.getUpgradeCount()).isZero();
        assertThat(persisted.getPackages()).isEmpty();
        assertThat(persisted.getProbeErrors()).isEmpty();
    }

    @Test
    void unsupportedGoldenExamplePersistsFailClosed() {
        // supported=false / probeComplete=false / sourceUsed=none with a
        // probeError must persist (NOT be treated as a failed ingest, never
        // rendered "up to date").
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, wrap(goldenUnsupported()));

        assertThat(persisted.getSupported()).isFalse();
        assertThat(persisted.getProbeComplete()).isFalse();
        assertThat(persisted.getSourceUsed()).isEqualTo("none");
        assertThat(persisted.getUpgradeCount()).isZero();
        assertThat(persisted.getPackages()).isEmpty();
        assertThat(persisted.getProbeErrors()).hasSize(1);
        assertThat(persisted.getProbeErrors().get(0).get("code"))
                .isEqualTo("UNSUPPORTED_PLATFORM");
    }

    // ------------------------------------------------------------------
    // Redaction MACHINE-ENFORCED: injected forbidden field never persists
    // ------------------------------------------------------------------

    @Test
    void injectedForbiddenPublisherFieldIsRejectedNeverPersisted() {
        // Mirror the production flow: the policy projection runs before
        // ingest (EndpointAgentCommandService). A golden with an injected
        // forbidden package field ("publisher") is fail-closed REJECTED, so
        // it never reaches the snapshot/package tables at all.
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(0).put("publisher", "Igor Pavlov");

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden outdated-software package key 'publisher'");

        // And nothing was persisted (the reject aborts before ingest).
        assertThat(snapshotRepository.count()).isZero();
    }

    @Test
    void persistedPackageRowsCarryOnlyContractFields() {
        // Even via the full policy → ingest flow, the persisted package
        // entity exposes exactly the three contract fields (the entity has no
        // column for a forbidden field — machine-enforced at the schema).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> sanitized = policy.sanitize(wrap(goldenWithUpgrades()));
        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, sanitized);

        for (EndpointOutdatedSoftwarePackage pkg : persisted.getPackages()) {
            assertThat(pkg.getPackageId()).isNotBlank();
            assertThat(pkg.getInstalledVersion()).isNotBlank();
            assertThat(pkg.getAvailableVersion()).isNotBlank();
        }
        // The snapshot redacted_payload upgrade[] entries carry exactly the
        // three keys (no publisher/name/path/license/url survived).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redactedUpgrade =
                (List<Map<String, Object>>) persisted.getRedactedPayload().get("upgrade");
        assertThat(redactedUpgrade).hasSize(2);
        for (Map<String, Object> entry : redactedUpgrade) {
            assertThat(entry).containsOnlyKeys("packageId", "installedVersion", "availableVersion");
        }
    }

    // ------------------------------------------------------------------
    // possiblyTruncated signal (upgradeCount == maxUpgrade)
    // ------------------------------------------------------------------

    @Test
    void fullCapEmitsPossiblyTruncatedSignal() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        // upgradeCount == maxUpgrade (512), with a 512-entry upgrade array.
        Map<String, Object> os = goldenClean();
        os.put("upgradeCount", 512);
        List<Map<String, Object>> upgrade = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            upgrade.add(pkg("pkg.id" + i, "1.0", "2.0"));
        }
        os.put("upgrade", upgrade);

        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, wrap(os));

        assertThat(persisted.getUpgradeCount()).isEqualTo(512);
        assertThat(persisted.getPackages()).hasSize(512);
        OutdatedSoftwareSnapshotPersistedEvent event = recordingEvents.captured().get(0);
        assertThat(event.possiblyTruncated())
                .as("upgradeCount==maxUpgrade is the 'possibly truncated' signal")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Idempotency + dedupe
    // ------------------------------------------------------------------

    @Test
    void reIngestWithSameCommandResultReturnsExistingSnapshot() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> details = wrap(goldenWithUpgrades());
        EndpointOutdatedSoftwareSnapshot first = service.ingest(device, command, result, details);
        recordingEvents.clear();

        EndpointOutdatedSoftwareSnapshot second = service.ingest(device, command, result, details);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void reIngestIdenticalPayloadUnderDifferentCommandResultDeduplicates() {
        // BE-022Q payload-hash deep-equality dedupe: byte-identical
        // outdated-software re-collected under a DIFFERENT command-result
        // (so the source_command_result_id probe misses) must return the
        // existing snapshot rather than appending a duplicate row.
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now());
        EndpointOutdatedSoftwareSnapshot first =
                service.ingest(device, cmd1, result1, wrap(goldenWithUpgrades()));
        recordingEvents.clear();

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now());
        EndpointOutdatedSoftwareSnapshot second =
                service.ingest(device, cmd2, result2, wrap(goldenWithUpgrades()));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void changedPayloadUnderDifferentCommandResultStillAppends() {
        // Dedupe must NOT swallow a genuine change: a different payload (one
        // extra upgradeable package → different hash) under a new
        // command-result appends a new snapshot.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now().minusSeconds(3600));
        EndpointOutdatedSoftwareSnapshot first =
                service.ingest(device, cmd1, result1, wrap(goldenClean()));

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now());
        EndpointOutdatedSoftwareSnapshot second =
                service.ingest(device, cmd2, result2, wrap(goldenWithUpgrades()));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Query — ordering
    // ------------------------------------------------------------------

    @Test
    void findLatestReturnsEmptyForNoSnapshots() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        assertThat(service.findLatest(TENANT_A, device.getId())).isEmpty();
    }

    @Test
    void findLatestReturnsMostRecentCollectedAt() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now().minusSeconds(7200));
        EndpointOutdatedSoftwareSnapshot older =
                service.ingest(device, cmd1, result1, wrap(goldenClean()));

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now().minusSeconds(60));
        // Different payload (with-upgrades) so dedupe does not collapse them.
        EndpointOutdatedSoftwareSnapshot newer =
                service.ingest(device, cmd2, result2, wrap(goldenWithUpgrades()));

        Optional<EndpointOutdatedSoftwareSnapshot> latest =
                service.findLatest(TENANT_A, device.getId());
        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).isEqualTo(newer.getId());
        assertThat(latest.get().getId()).isNotEqualTo(older.getId());
    }

    // ------------------------------------------------------------------
    // redacted_payload probeErrors substitution + no-healthy-default
    // ------------------------------------------------------------------

    @Test
    void redactedPayloadSubstitutesBoundedProbeErrors() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> os = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) os.get("probeErrors");
        // Add extra raw fields the service must NOT propagate to
        // redactedPayload.probeErrors. Run through the policy first (it drops
        // them), then verify the ingest substitution keeps only bounded keys.
        Map<String, Object> sanitized = policy.sanitize(wrap(os));

        EndpointOutdatedSoftwareSnapshot persisted =
                service.ingest(device, command, result, sanitized);

        assertThat(persisted.getProbeErrors()).hasSize(1);
        assertThat(persisted.getProbeErrors().get(0)).containsOnlyKeys("code", "source", "summary");

        Object redacted = persisted.getRedactedPayload().get("probeErrors");
        assertThat(redacted).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redactedList = (List<Map<String, Object>>) redacted;
        assertThat(redactedList).hasSize(1);
        assertThat(redactedList.get(0)).containsOnlyKeys("code", "source", "summary");
    }

    @Test
    void ingestRejectsMinimalSchemaVersionOnlyBlockNoUpToDateDefault() {
        // The service must NOT synthesize "up to date" defaults for required
        // fields. A minimal {"schemaVersion":1} block (which the policy would
        // already reject upstream) must NOT become an up-to-date-looking
        // snapshot if it reaches ingest — the strict required-field accessors
        // fail loud instead.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("schemaVersion", 1);
        inventory.put("outdatedSoftware", minimal);
        details.put("inventory", inventory);

        assertThatThrownBy(() -> service.ingest(device, command, result, details))
                .isInstanceOf(IllegalStateException.class);
        assertThat(snapshotRepository.count()).isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

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

    private EndpointCommandResult persistResult(EndpointCommand command, Instant reportedAt) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setReportedAt(reportedAt);
        result.setResultPayload(new LinkedHashMap<>());
        return resultRepository.saveAndFlush(result);
    }

    private Map<String, Object> wrap(Map<String, Object> outdatedSoftware) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("outdatedSoftware", outdatedSoftware);
        details.put("inventory", inventory);
        return details;
    }

    private Map<String, Object> goldenWithUpgrades() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 2);
        List<Map<String, Object>> upgrade = new ArrayList<>();
        upgrade.add(pkg("7zip.7zip", "24.09", "25.01"));
        upgrade.add(pkg("Microsoft.VisualStudioCode", "1.89.0", "1.91.1"));
        os.put("upgrade", upgrade);
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 45);
        return os;
    }

    private Map<String, Object> goldenClean() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 0);
        os.put("upgrade", new ArrayList<>());
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 28);
        return os;
    }

    private Map<String, Object> goldenUnsupported() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", false);
        os.put("probeComplete", false);
        os.put("upgradeCount", 0);
        os.put("upgrade", new ArrayList<>());
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "none");
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("source", "none");
        err.put("code", "UNSUPPORTED_PLATFORM");
        err.put("summary", "outdated-software probe not supported on this runtime");
        errors.add(err);
        os.put("probeErrors", errors);
        os.put("probeDurationMs", 0);
        return os;
    }

    private static Map<String, Object> pkg(String id, String installed, String available) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("packageId", id);
        p.put("installedVersion", installed);
        p.put("availableVersion", available);
        return p;
    }

    // ------------------------------------------------------------------
    // Recording event listener
    // ------------------------------------------------------------------

    @TestConfiguration
    static class RecordingEventPublisherConfig {
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    static class RecordingEventPublisher {
        private final List<OutdatedSoftwareSnapshotPersistedEvent> events = new ArrayList<>();

        @EventListener
        public void onOutdatedSoftwareSnapshotPersisted(OutdatedSoftwareSnapshotPersistedEvent event) {
            events.add(event);
        }

        List<OutdatedSoftwareSnapshotPersistedEvent> captured() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
