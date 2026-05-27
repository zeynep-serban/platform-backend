package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-020I — Software Inventory ingest + query tests (Faz 22.5.3A).
 *
 * <p>Slice runs on H2 (notify-orchestrator-style PG Testcontainers
 * integration coverage lives in
 * {@code EndpointSoftwareInventoryPostgresIntegrationTest}).
 * Covers Codex 019e6ab2 iter-2 acceptance gates:
 *
 * <ul>
 *   <li>Summary-only ingest does NOT erase a prior full apps snapshot</li>
 *   <li>Full apps replacement is atomic + flips {@code apps_available}</li>
 *   <li>{@code apps_available} latches — never flipped back by summary-only</li>
 *   <li>Audit emit (BE-016 hash-chain) for INGESTED / REPLACED / SUMMARY_UPDATED</li>
 *   <li>Tenant isolation (cross-tenant ingest returns separate snapshots)</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        TimeConfig.class,
        EndpointSoftwareInventoryService.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class,
        com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy.class,
        com.example.endpointadmin.security.WinGetEgressPayloadPolicy.class
})
class EndpointSoftwareInventoryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private EndpointSoftwareInventoryService service;

    @Autowired
    private EndpointSoftwareInventorySnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void firstIngestWithFullAppsEmitsIngestedAndStoresItems() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM"),
                appMap("Notepad++", "8.6", "Don Ho", "HKLM_WOW6432")
        ));

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.isAppsAvailable()).isTrue();
        assertThat(saved.getItems()).hasSize(2);
        assertThat(saved.getAppCount()).isEqualTo(2);
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareInventoryService.EVENT_INGESTED);
    }

    @Test
    void fullAppsAfterFullReplacesItemsAtomicallyAndEmitsReplaced() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        // First ingest
        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult res1 = persistResult(cmd1);
        service.ingest(device, cmd1, res1, summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM")
        )));
        flushAndClear();

        // Second ingest with a different app set
        EndpointDevice reloaded = deviceRepository.findById(device.getId())
                .orElseThrow();
        EndpointCommand cmd2 = persistCommand(reloaded);
        EndpointCommandResult res2 = persistResult(cmd2);
        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(reloaded, cmd2, res2, summaryWithApps(List.of(
                        appMap("Firefox", "126.0", "Mozilla", "HKLM"),
                        appMap("VLC", "3.0.21", "VideoLAN", "HKLM_WOW6432")
                )));

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.REPLACED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.getItems()).extracting(
                com.example.endpointadmin.model.EndpointSoftwareInventoryItem::getDisplayName)
                .containsExactlyInAnyOrder("Firefox", "VLC");
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareInventoryService.EVENT_REPLACED);
    }

    @Test
    void summaryOnlyAfterFullPreservesItemsAndKeepsAppsAvailableTrue() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult res1 = persistResult(cmd1);
        service.ingest(device, cmd1, res1, summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM")
        )));
        flushAndClear();

        // Summary-only follow-up: no `apps` key.
        EndpointDevice reloaded = deviceRepository.findById(device.getId())
                .orElseThrow();
        EndpointCommand cmd2 = persistCommand(reloaded);
        EndpointCommandResult res2 = persistResult(cmd2);
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", 42);
        inventory.put("wingetReady", true);
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(reloaded, cmd2, res2, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.SUMMARY_UPDATED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        // Items preserved.
        assertThat(saved.getItems()).hasSize(1);
        // apps_available stays true (Codex 019e6ab2 iter-2 acceptance).
        assertThat(saved.isAppsAvailable()).isTrue();
        // app_count reflects the latest summary.
        assertThat(saved.getAppCount()).isEqualTo(42);
        assertThat(auditEventTypes(TENANT_A))
                .contains(EndpointSoftwareInventoryService
                        .EVENT_SUMMARY_UPDATED);
    }

    @Test
    void summaryOnlyFirstIngestDoesNotFlipAppsAvailableTrue() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", 17);
        inventory.put("wingetReady", false);
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.isAppsAvailable()).isFalse();
        assertThat(saved.getItems()).isEmpty();
    }

    @Test
    void crossTenantSnapshotsAreIsolated() {
        EndpointDevice deviceA = persistDevice(TENANT_A, "PC-A");
        EndpointDevice deviceB = persistDevice(TENANT_B, "PC-A"); // same hostname different tenant
        service.ingest(deviceA, persistCommand(deviceA),
                persistResult(persistCommand(deviceA)),
                summaryWithApps(List.of(
                        appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM"))));
        service.ingest(deviceB, persistCommand(deviceB),
                persistResult(persistCommand(deviceB)),
                summaryWithApps(List.of(
                        appMap("Firefox", "126.0", "Mozilla", "HKLM"))));
        flushAndClear();

        EndpointSoftwareInventorySnapshot snapA = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, deviceA.getId())
                .orElseThrow();
        EndpointSoftwareInventorySnapshot snapB = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_B, deviceB.getId())
                .orElseThrow();
        assertThat(snapA.getItems()).extracting(
                com.example.endpointadmin.model.EndpointSoftwareInventoryItem::getDisplayName)
                .containsExactly("7-Zip");
        assertThat(snapB.getItems()).extracting(
                com.example.endpointadmin.model.EndpointSoftwareInventoryItem::getDisplayName)
                .containsExactly("Firefox");
    }

    @Test
    void summaryOnlyWrapperFirstIngestCreatesSnapshotWithAppsAvailableFalse() {
        // Codex 019e6ac8 iter-2 P1 absorb: details.inventory.summary.{...}
        // wrapper layout must be recognized + ingested (first ingest path,
        // no apps[] array).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("supported", true);
        summary.put("appCount", 42);
        summary.put("wingetReady", true);
        inventory.put("summary", summary);
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.isAppsAvailable()).isFalse();
        assertThat(saved.getItems()).isEmpty();
        assertThat(saved.getAppCount()).isEqualTo(42);
    }

    @Test
    void summaryOnlyWrapperAfterFullPreservesItemsAndKeepsAppsAvailableTrue() {
        // Codex 019e6ac8 iter-2 P1 absorb: full apps first, then a
        // details.inventory.summary.{...} wrapper-shaped summary-only
        // update — items must survive + apps_available stays true.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult res1 = persistResult(cmd1);
        service.ingest(device, cmd1, res1, summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM"))));
        flushAndClear();

        EndpointDevice reloaded = deviceRepository.findById(device.getId())
                .orElseThrow();
        EndpointCommand cmd2 = persistCommand(reloaded);
        EndpointCommandResult res2 = persistResult(cmd2);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("appCount", 123);
        summary.put("wingetReady", false);
        inventory.put("summary", summary);
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(reloaded, cmd2, res2, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.SUMMARY_UPDATED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.isAppsAvailable()).isTrue();
        assertThat(saved.getAppCount()).isEqualTo(123);
    }

    @Test
    void agentSoftwareWrapperShapeIngestsFullSnapshot() {
        // BE-020I follow-up (Codex 019e6aef iter-1 P1 absorb): agent ships
        // details.inventory.software.{schemaVersion, apps, appCount,
        // wingetReady, ...}. The extractInventoryMap helper must recognize
        // this wrapper layout and ingest the software sub-map as the
        // canonical inventory node — without this branch the wrapper would
        // SKIP and full software ingest would never land.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> software = new LinkedHashMap<>();
        software.put("schemaVersion", 1);
        software.put("supported", true);
        software.put("appCount", 1);
        software.put("wingetReady", true);
        software.put("apps", List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM")));
        inventory.put("software", software);
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.isAppsAvailable()).isTrue();
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getAppCount()).isEqualTo(1);
        assertThat(saved.getWingetReady()).isTrue();
    }

    @Test
    void agentSoftwareWrapperWithoutSoftwareKeysIsSkipped() {
        // Defensive: agent could ship `details.inventory.software` as a
        // map that does NOT carry recognized keys (e.g. only error /
        // unsupported markers). hasRecognizedSoftwareKey requires at
        // least one canonical field; without one the wrapper itself is
        // skipped and the outer inventory node guard applies as before.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> software = new LinkedHashMap<>();
        software.put("error", "registry_blocked");
        inventory.put("software", software);
        // Outer inventory also without canonical keys → overall SKIPPED.
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.SKIPPED);
        assertThat(snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId()))
                .isEmpty();
    }

    @Test
    void ingestSkippedWhenNoInventoryShapePresent() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = Map.of(
                "unrelated", "value",
                "someNumber", 42);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.SKIPPED);
        assertThat(snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId()))
                .isEmpty();
    }

    // ────────────────────────────────────────────────────────────────
    // BE-021A AG-026A wingetEgress ingest tests

    @Test
    void wingetEgressBlockMaterializedAlongsideSummaryAndApps() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", 1);
        inventory.put("wingetReady", true);
        inventory.put("apps", List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM")));
        inventory.put("wingetEgress", wellFormedWingetEgress());
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.getWingetEgress()).isNotNull();
        assertThat(saved.getWingetEgressSchemaVersion()).isEqualTo(1);
        assertThat(saved.getWingetEgressCollectedAt()).isNotNull();
        assertThat(saved.getLatestWingetEgressCommandResult())
                .as("egress result FK must point to ingest result")
                .isNotNull();
        assertThat(saved.getLatestWingetEgressCommandResult().getId())
                .isEqualTo(result.getId());
    }

    @Test
    void wingetEgressOnlyPayloadIsAcceptedFirstIngest() {
        // includeWinGetEgress=true && includeSoftware=false agent path:
        // wingetEgress arrives without an inventory.software block.
        // The ingest service must still create the snapshot row so the
        // egress evidence has a place to live (Codex 019e6b88 wire-
        // shape contract).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("wingetEgress", wellFormedWingetEgress());
        details.put("inventory", inventory);

        EndpointSoftwareInventoryService.IngestOutcome outcome =
                service.ingest(device, command, result, details);

        assertThat(outcome).isEqualTo(
                EndpointSoftwareInventoryService.IngestOutcome.INGESTED);
        flushAndClear();

        EndpointSoftwareInventorySnapshot saved = snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId())
                .orElseThrow();
        assertThat(saved.getWingetEgress()).isNotNull();
        // Summary fields stay at defaults — agent did not ship them.
        assertThat(saved.isAppsAvailable()).isFalse();
        assertThat(saved.getAppCount()).isNull();
    }

    @Test
    void wingetEgressSchemaMismatchIsRejected() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> egress = new LinkedHashMap<>(wellFormedWingetEgress());
        egress.put("schemaVersion", 999); // not the pinned schema
        inventory.put("wingetEgress", egress);
        details.put("inventory", inventory);

        try {
            service.ingest(device, command, result, details);
            assertThat(false).as("expected schema-mismatch reject").isTrue();
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getMessage()).contains("schemaVersion");
        }
        flushAndClear();

        // No snapshot row was inserted for the rejected payload.
        assertThat(snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId()))
                .isEmpty();
    }

    @Test
    void wingetEgressForbiddenPiiIsRejected() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        Map<String, Object> egress = new LinkedHashMap<>(wellFormedWingetEgress());
        // Smuggle a SID literal into a free-form error reason: the
        // WinGetEgressPayloadPolicy must catch it via the inherited
        // SoftwareInventoryPayloadPolicy regex set.
        egress.put("probeError",
                "Failure at S-1-5-21-1111111111-2222222222-3333333333-1001");
        inventory.put("wingetEgress", egress);
        details.put("inventory", inventory);

        try {
            service.ingest(device, command, result, details);
            assertThat(false).as("expected PII reject").isTrue();
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getMessage()).contains("SID");
        }
        flushAndClear();
        assertThat(snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT_A, device.getId()))
                .isEmpty();
    }

    @Test
    void wingetEgressAuditMetadataPresenceFlagOnly() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("apps", List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM")));
        inventory.put("wingetEgress", wellFormedWingetEgress());
        details.put("inventory", inventory);

        service.ingest(device, command, result, details);
        flushAndClear();

        // Audit metadata exposes the schemaVersion + presence boolean
        // but MUST NOT carry the raw egress JSONB (Codex 019e6b88
        // audit boundary).
        var lastAudit = auditRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_A)
                .stream()
                .filter(e -> e.getEventType()
                        .startsWith("ENDPOINT_SOFTWARE_INVENTORY"))
                .findFirst()
                .orElseThrow();
        assertThat(lastAudit.getMetadata())
                .containsEntry("wingetEgressIngested", true)
                .containsEntry("wingetEgressSchemaVersion", 1);
        // The raw payload sub-keys must NOT be in the audit metadata.
        assertThat(lastAudit.getMetadata())
                .doesNotContainKeys("sources", "packageQuery", "egress");
    }

    private Map<String, Object> wellFormedWingetEgress() {
        Map<String, Object> egress = new LinkedHashMap<>();
        egress.put("supported", true);
        egress.put("schemaVersion", 1);
        egress.put("probeDurationMs", 4380);
        egress.put("timeout", false);
        egress.put("sources", List.of(Map.of(
                "name", "winget",
                "argument", "https://cdn.winget.microsoft.com/cache",
                "type", "Microsoft.PreIndexed.Package",
                "trustLevel", "Trusted")));
        Map<String, Object> pq = new LinkedHashMap<>();
        pq.put("packageId", "7zip.7zip");
        pq.put("found", true);
        pq.put("exitCode", 0);
        pq.put("durationMs", 1820);
        pq.put("timeout", false);
        egress.put("packageQuery", pq);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("dns", List.of(Map.of(
                "target", "cdn.winget.microsoft.com",
                "ok", true,
                "durationMs", 12)));
        e.put("tcp", List.of(Map.of(
                "target", "cdn.winget.microsoft.com:443",
                "ok", true,
                "durationMs", 38)));
        e.put("https", List.of(Map.of(
                "target", "https://cdn.winget.microsoft.com",
                "ok", true,
                "durationMs", 152)));
        e.put("proxyConfigured", false);
        egress.put("egress", e);
        return egress;
    }

    @Test
    void unsupportedInstallSourceRejectsIngest() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command);

        Map<String, Object> details = summaryWithApps(List.of(Map.of(
                "displayName", "7-Zip",
                "installSource", "HKCU"
        )));

        try {
            service.ingest(device, command, result, details);
            assertThat(false).as("expected reject").isTrue();
        } catch (IllegalArgumentException ex) {
            assertThat(ex.getMessage())
                    .contains("installSource");
        }
    }

    // ----------------------------------------------------------------
    // Helpers

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> auditEventTypes(UUID tenantId) {
        return auditRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(tenantId)
                .stream()
                .map(EndpointAuditEvent::getEventType)
                .toList();
    }

    private Map<String, Object> appMap(String name, String version,
                                        String publisher, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", name);
        m.put("displayVersion", version);
        m.put("publisher", publisher);
        m.put("installSource", source);
        m.put("uninstallStringPresent", true);
        return m;
    }

    private Map<String, Object> summaryWithApps(List<Map<String, Object>> apps) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", apps.size());
        inventory.put("wingetReady", true);
        inventory.put("apps", apps);
        details.put("inventory", inventory);
        return details;
    }

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand persistCommand(EndpointDevice device) {
        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(device.getTenantId());
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.COLLECT_INVENTORY);
        cmd.setIdempotencyKey("inv-" + UUID.randomUUID());
        cmd.setPayload(Map.of());
        cmd.setStatus(CommandStatus.SUCCEEDED);
        cmd.setIssuedBySubject("admin@example.com");
        cmd.setIssuedAt(Instant.now());
        return commandRepository.saveAndFlush(cmd);
    }

    private EndpointCommandResult persistResult(EndpointCommand command) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setResultPayload(Map.of());
        result.setReportedAt(Instant.now());
        return resultRepository.saveAndFlush(result);
    }

}
