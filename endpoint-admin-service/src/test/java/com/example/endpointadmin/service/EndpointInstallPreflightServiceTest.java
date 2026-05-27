package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstalledState;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.service.EndpointInstallPreflightService.Reason;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-021A — pure-evaluator unit tests for
 * {@link EndpointInstallPreflightService#compute} (Faz 22.5).
 *
 * <p>Covers Codex 019e6b88 plan-time AGREE decision matrix:
 *
 * <ul>
 *   <li>Catalog gate: status/enabled/provider/sourceType/installerType</li>
 *   <li>Device gate: ONLINE vs OFFLINE/STALE/DECOMMISSIONED</li>
 *   <li>Inventory gate: missing/unsupported/stale/apps-unavailable</li>
 *   <li>WinGet gate: wingetReady/egress missing/schema mismatch/fixed
 *       probe package mismatch (7zip-only)/package query found</li>
 *   <li>InstalledState: WINGET items with apps_available match by
 *       displayName, non-WINGET → UNKNOWN, apps_unavailable → UNKNOWN</li>
 *   <li>Reason vocabulary stability (machine codes match spec)</li>
 *   <li>Evidence refs populated when underlying rows exist</li>
 * </ul>
 *
 * <p>Pure JUnit — no Spring context, no DB. Repository-backed paths
 * are covered in the controller integration test.
 */
class EndpointInstallPreflightServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-05-28T12:00:00Z");
    private static final UUID TENANT =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final EndpointInstallPreflightService service =
            new EndpointInstallPreflightService(
                    null, null, null,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    // ────────────────────────────────────────────────────────────────
    // Happy path

    @Test
    void passWhenAllGatesSatisfied() {
        EndpointSoftwareCatalogItem catalog = approvedCatalogItem("7zip.7zip");
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog, snapshot);

        assertThat(response.decision()).isEqualTo(InstallPreflightDecision.PASS);
        assertThat(response.blockingReasons()).isEmpty();
        assertThat(response.warnings()).isEmpty();
        assertThat(response.installedState()).isEqualTo(InstalledState.NOT_INSTALLED);
        assertThat(response.catalogItemId()).isEqualTo("7zip.7zip");
        assertThat(response.evidence()).isNotNull();
        assertThat(response.evidence().wingetEgressSchemaVersion()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────
    // Catalog gate

    @Test
    void blockWhenCatalogDraft() {
        EndpointSoftwareCatalogItem catalog = approvedCatalogItem("7zip.7zip");
        catalog.setStatus(CatalogItemStatus.DRAFT);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog,
                fullEvidenceSnapshot());

        assertThat(response.decision()).isEqualTo(InstallPreflightDecision.BLOCK);
        assertThat(response.blockingReasons()).contains(Reason.CATALOG_ITEM_DRAFT.code());
    }

    @Test
    void blockWhenCatalogRevoked() {
        EndpointSoftwareCatalogItem catalog = approvedCatalogItem("7zip.7zip");
        catalog.setStatus(CatalogItemStatus.REVOKED);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog, fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.CATALOG_ITEM_REVOKED.code());
    }

    @Test
    void blockWhenApprovedButDisabled() {
        EndpointSoftwareCatalogItem catalog = approvedCatalogItem("7zip.7zip");
        catalog.setEnabled(false);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog, fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.CATALOG_ITEM_DISABLED.code());
    }

    @Test
    void blockWhenInstallerTypeMissing() {
        EndpointSoftwareCatalogItem catalog = approvedCatalogItem("7zip.7zip");
        catalog.setInstallerType(null);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog, fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.INSTALLER_TYPE_NOT_INSTALL_READY.code());
    }

    // ────────────────────────────────────────────────────────────────
    // Device gate

    @Test
    void blockWhenDeviceOffline() {
        EndpointDevice device = onlineDevice();
        device.setStatus(DeviceStatus.OFFLINE);

        InstallPreflightResponse response = service.compute(
                device, approvedCatalogItem("7zip.7zip"),
                fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.DEVICE_NOT_ONLINE.code());
    }

    @Test
    void blockWhenDeviceDecommissioned() {
        EndpointDevice device = onlineDevice();
        device.setStatus(DeviceStatus.DECOMMISSIONED);

        InstallPreflightResponse response = service.compute(
                device, approvedCatalogItem("7zip.7zip"),
                fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.DEVICE_DECOMMISSIONED.code());
    }

    // ────────────────────────────────────────────────────────────────
    // Inventory gate

    @Test
    void blockWhenInventoryMissing() {
        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), null);

        assertThat(response.blockingReasons())
                .contains(Reason.INVENTORY_MISSING.code());
    }

    @Test
    void blockWhenInventoryUnsupported() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        snapshot.setSupported(false);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.INVENTORY_UNSUPPORTED.code());
    }

    @Test
    void warnWhenInventoryStale() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        // Stale: every evidence stream 48h old.
        setUpdatedAt(snapshot, NOW.minus(Duration.ofHours(48)));
        snapshot.setSummaryCollectedAt(NOW.minus(Duration.ofHours(48)));
        snapshot.setAppsCollectedAt(NOW.minus(Duration.ofHours(48)));
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW.minus(Duration.ofHours(48)));

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.INVENTORY_STALE.code());
        // INVENTORY_STALE alone does not block.
        assertThat(response.decision()).isEqualTo(InstallPreflightDecision.WARN);
    }

    @Test
    void warnWhenAppsCollectedAtStaleButUpdatedAtFresh() {
        // Codex 019e6ba4 iter-1 P1#2 absorb regression: a wingetEgress-
        // only ingest refreshes updatedAt but apps_collected_at stays
        // 48h old. The previous implementation (which read updatedAt)
        // would have falsely classified this snapshot as fresh; the
        // new evidence-stream-by-stream freshness check must catch it.
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        setUpdatedAt(snapshot, NOW); // fresh bulk timestamp
        snapshot.setSummaryCollectedAt(NOW);
        snapshot.setAppsCollectedAt(NOW.minus(Duration.ofHours(48))); // stale apps
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.INVENTORY_STALE.code());
    }

    @Test
    void warnWhenWingetEgressCollectedAtStaleButUpdatedAtFresh() {
        // Symmetric regression: a summary-only ingest refreshes
        // updatedAt but wingetEgress_collected_at stays 48h old. The
        // egress evidence the install-preflight relies on is stale and
        // the operator must see the signal.
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        setUpdatedAt(snapshot, NOW);
        snapshot.setSummaryCollectedAt(NOW);
        snapshot.setAppsCollectedAt(NOW);
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW.minus(Duration.ofHours(48)));

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.INVENTORY_STALE.code());
    }

    @Test
    void inventoryStaleEmittedOnceEvenWhenMultipleStreamsStale() {
        // Three stale streams should still produce a single
        // INVENTORY_STALE warning entry — the dedupe lives in the
        // service-level boolean rollup, not in caller filtering.
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        setUpdatedAt(snapshot, NOW.minus(Duration.ofHours(72)));
        snapshot.setSummaryCollectedAt(NOW.minus(Duration.ofHours(72)));
        snapshot.setAppsCollectedAt(NOW.minus(Duration.ofHours(72)));
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW.minus(Duration.ofHours(72)));

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        long staleCount = response.warnings().stream()
                .filter(Reason.INVENTORY_STALE.code()::equals)
                .count();
        assertThat(staleCount).isEqualTo(1L);
    }

    @Test
    void warnWhenAppsUnavailable() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(false);
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.APPS_UNAVAILABLE.code());
        assertThat(response.warnings())
                .contains(Reason.INSTALLED_STATE_UNKNOWN.code());
        assertThat(response.installedState()).isEqualTo(InstalledState.UNKNOWN);
    }

    // ────────────────────────────────────────────────────────────────
    // WinGet gate

    @Test
    void blockWhenWingetNotReady() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        snapshot.setWingetReady(false);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_NOT_READY.code());
    }

    @Test
    void blockWhenWingetEgressMissing() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        snapshot.setWingetEgress(null);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_EGRESS_MISSING.code());
    }

    @Test
    void blockWhenEgressSupportedFalse() {
        // Codex 019e6ba4 iter-2 absorb (P1): the validator tolerates
        // wingetEgress.supported=false (non-Windows stub legitimate),
        // but the service must STILL block on it. Without this check
        // a malformed payload with supported=false + found=true +
        // empty egress arrays could reach PASS.
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        Map<String, Object> egress = new LinkedHashMap<>(packageFoundEgress());
        egress.put("supported", false);
        snapshot.setWingetEgress(egress);
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_EGRESS_UNSUPPORTED.code());
        assertThat(response.decision()).isEqualTo(InstallPreflightDecision.BLOCK);
    }

    @Test
    void blockWhenEgressSchemaUnsupported() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        snapshot.setWingetEgressSchemaVersion(999);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_EGRESS_SCHEMA_UNSUPPORTED.code());
    }

    @Test
    void blockWhenCatalogPackageIdNonPilot() {
        EndpointSoftwareCatalogItem catalog =
                approvedCatalogItem("Notepad.Notepad");

        InstallPreflightResponse response = service.compute(
                onlineDevice(), catalog, fullEvidenceSnapshot());

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_FIXED_PROBE_PACKAGE_MISMATCH.code());
    }

    @Test
    void blockWhenPackageQueryNotFound() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(true);
        snapshot.setWingetEgress(packageNotFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.blockingReasons())
                .contains(Reason.WINGET_PACKAGE_QUERY_NOT_FOUND.code());
    }

    @Test
    void warnWhenEgressTimeoutPartial() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        Map<String, Object> egress = new LinkedHashMap<>(packageFoundEgress());
        egress.put("timeout", true);
        snapshot.setWingetEgress(egress);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.WINGET_EGRESS_PARTIAL.code());
        assertThat(response.decision()).isEqualTo(InstallPreflightDecision.WARN);
    }

    @Test
    void warnWhenSourceListErrorPresent() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        Map<String, Object> egress = new LinkedHashMap<>(packageFoundEgress());
        egress.put("sourceListError", "winget source list non-zero exit");
        snapshot.setWingetEgress(egress);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.WINGET_SOURCE_LIST_WARNING.code());
    }

    @Test
    void warnWhenDnsCheckFailed() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        Map<String, Object> egress = new LinkedHashMap<>(packageFoundEgress());
        Map<String, Object> egressBlock = new LinkedHashMap<>(asMap(egress.get("egress")));
        egressBlock.put("dns", List.of(
                Map.of("target", "cdn.winget.microsoft.com",
                        "ok", false, "durationMs", 1000,
                        "errorReason", "lookup failed")));
        egress.put("egress", egressBlock);
        snapshot.setWingetEgress(egress);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.warnings())
                .contains(Reason.WINGET_EGRESS_PARTIAL.code());
    }

    // ────────────────────────────────────────────────────────────────
    // installedState

    @Test
    void installedStateInstalledWhenInventoryItemMatchesDisplayName() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        snapshot.getItems().add(invItem("7-Zip 24.07 (x64 tr)"));

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.installedState()).isEqualTo(InstalledState.INSTALLED);
    }

    @Test
    void installedStateNotInstalledWhenNoMatch() {
        EndpointSoftwareInventorySnapshot snapshot = fullEvidenceSnapshot();
        snapshot.getItems().add(invItem("Firefox 126.0"));

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.installedState()).isEqualTo(InstalledState.NOT_INSTALLED);
    }

    @Test
    void installedStateUnknownWhenAppsUnavailable() {
        EndpointSoftwareInventorySnapshot snapshot = healthySnapshot(false);
        snapshot.setWingetEgress(packageFoundEgress());
        snapshot.setWingetEgressSchemaVersion(1);
        snapshot.setWingetEgressCollectedAt(NOW);

        InstallPreflightResponse response = service.compute(
                onlineDevice(), approvedCatalogItem("7zip.7zip"), snapshot);

        assertThat(response.installedState()).isEqualTo(InstalledState.UNKNOWN);
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers

    private static EndpointDevice onlineDevice() {
        EndpointDevice d = new EndpointDevice();
        // EndpointDevice.id is @GeneratedValue — the setter does not exist
        // on the entity, so tests inject a synthetic UUID via reflection.
        setField(d, "id", UUID.randomUUID());
        d.setTenantId(TENANT);
        d.setStatus(DeviceStatus.ONLINE);
        return d;
    }

    private static EndpointSoftwareCatalogItem approvedCatalogItem(String pkgId) {
        EndpointSoftwareCatalogItem c = new EndpointSoftwareCatalogItem();
        setField(c, "id", UUID.randomUUID());
        c.setTenantId(TENANT);
        c.setCatalogItemId(pkgId);
        c.setStatus(CatalogItemStatus.APPROVED);
        c.setEnabled(true);
        c.setProvider(CatalogProvider.WINGET);
        c.setSourceType(CatalogSourceType.WINGET);
        c.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        c.setPackageId(pkgId);
        // Mirror production catalog convention: displayName is a clean
        // human-readable label ("7-Zip"), packageId is the WinGet id
        // ("7zip.7zip"). The MVP installedState heuristic matches the
        // inventory item's display_name against EITHER value, so the
        // distinction keeps the test honest about what production data
        // looks like.
        c.setDisplayName("7zip.7zip".equalsIgnoreCase(pkgId) ? "7-Zip" : pkgId);
        // lastUpdatedAt + version are entity-managed (@PreUpdate /
        // @Version); inject via reflection for deterministic tests.
        setField(c, "lastUpdatedAt", NOW.minus(Duration.ofMinutes(5)));
        setField(c, "version", 1L);
        return c;
    }

    private static EndpointSoftwareInventorySnapshot healthySnapshot(boolean appsAvailable) {
        EndpointSoftwareInventorySnapshot s = new EndpointSoftwareInventorySnapshot();
        s.setTenantId(TENANT);
        s.setDevice(onlineDevice());
        s.setSchemaVersion(1);
        s.setSupported(true);
        s.setWingetReady(true);
        s.setAppsAvailable(appsAvailable);
        s.setSummaryCollectedAt(NOW);
        if (appsAvailable) {
            s.setAppsCollectedAt(NOW);
        }
        setUpdatedAt(s, NOW);
        return s;
    }

    private static EndpointSoftwareInventorySnapshot fullEvidenceSnapshot() {
        EndpointSoftwareInventorySnapshot s = healthySnapshot(true);
        s.setWingetEgress(packageFoundEgress());
        s.setWingetEgressSchemaVersion(1);
        s.setWingetEgressCollectedAt(NOW);
        return s;
    }

    private static EndpointSoftwareInventoryItem invItem(String displayName) {
        EndpointSoftwareInventoryItem item = new EndpointSoftwareInventoryItem();
        item.setTenantId(TENANT);
        item.setDisplayName(displayName);
        item.setInstallSource(SoftwareInstallSource.HKLM);
        return item;
    }

    private static Map<String, Object> packageFoundEgress() {
        Map<String, Object> egress = baseEgress();
        Map<String, Object> pq = new LinkedHashMap<>();
        pq.put("packageId", "7zip.7zip");
        pq.put("found", true);
        pq.put("exitCode", 0);
        pq.put("durationMs", 1820);
        pq.put("timeout", false);
        egress.put("packageQuery", pq);
        return egress;
    }

    private static Map<String, Object> packageNotFoundEgress() {
        Map<String, Object> egress = baseEgress();
        Map<String, Object> pq = new LinkedHashMap<>();
        pq.put("packageId", "7zip.7zip");
        pq.put("found", false);
        pq.put("exitCode", 0);
        pq.put("durationMs", 1820);
        pq.put("timeout", false);
        egress.put("packageQuery", pq);
        return egress;
    }

    private static Map<String, Object> baseEgress() {
        Map<String, Object> egress = new LinkedHashMap<>();
        egress.put("supported", true);
        egress.put("schemaVersion", 1);
        egress.put("probeDurationMs", 4380);
        egress.put("timeout", false);
        egress.put("sources", List.of(
                Map.of("name", "winget",
                        "argument", "https://cdn.winget.microsoft.com/cache")));
        Map<String, Object> egressBlock = new LinkedHashMap<>();
        egressBlock.put("dns", new ArrayList<>(List.of(
                Map.of("target", "cdn.winget.microsoft.com",
                        "ok", true, "durationMs", 12))));
        egressBlock.put("tcp", new ArrayList<>(List.of(
                Map.of("target", "cdn.winget.microsoft.com:443",
                        "ok", true, "durationMs", 38))));
        egressBlock.put("https", new ArrayList<>(List.of(
                Map.of("target", "https://cdn.winget.microsoft.com",
                        "ok", true, "durationMs", 152))));
        egressBlock.put("proxyConfigured", false);
        egress.put("egress", egressBlock);
        return egress;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * The entity uses {@code @PreUpdate} to set updatedAt; we override
     * via reflection for tests that need a stale value.
     */
    private static void setUpdatedAt(EndpointSoftwareInventorySnapshot snapshot,
                                      Instant value) {
        setField(snapshot, "updatedAt", value);
    }

    /**
     * Generic reflection setter — walks the class hierarchy until the
     * field is found (handles entity fields declared on superclasses or
     * with private visibility).
     */
    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError(
                "Field " + fieldName + " not found on " + target.getClass());
    }
}
