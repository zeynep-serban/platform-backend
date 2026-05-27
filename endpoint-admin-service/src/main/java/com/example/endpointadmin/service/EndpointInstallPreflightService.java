package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightEvidence;
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
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.WinGetEgressPayloadPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-021A — Install Preflight Service (Faz 22.5).
 *
 * <p>Computes the PASS / WARN / BLOCK decision for installing a given
 * BE-020 catalog item on a given BE-020I-inventoried device. The
 * decision is computed on-demand from three live inputs:
 *
 * <ol>
 *   <li>BE-020 catalog row — {@code status / enabled / provider /
 *       sourceType / installerType / packageId}.</li>
 *   <li>BE-020I inventory snapshot — {@code wingetReady} + apps list
 *       (when {@code apps_available=true}) + freshness stamps.</li>
 *   <li>AG-026A wingetEgress evidence — {@code packageQuery.found} for
 *       the pilot {@code 7zip.7zip} id + DNS/TCP/HTTPS reachability
 *       (no caching; recomputed on every GET).</li>
 * </ol>
 *
 * <p>Codex 019e6b88 plan-time AGREE — kilit kararlar:
 *
 * <ul>
 *   <li><b>No decision persistence</b>: every GET recomputes from
 *       scratch. AG-027 / BE-021 will recompute again at command
 *       creation; reusing a cached PASS is forbidden.</li>
 *   <li><b>Fixed-probe pilot constraint</b>: AG-026A only probes
 *       {@code 7zip.7zip}. A catalog item with a different
 *       {@code packageId} cannot claim PASS via this evidence — the
 *       service emits {@code BLOCK winget_fixed_probe_package_mismatch}
 *       until a per-package probe ships.</li>
 *   <li><b>WinGet readiness is provider-bound</b> (not detection-bound):
 *       {@code wingetReady} + {@code wingetEgress} gate every catalog
 *       item with {@code provider == WINGET}. The {@code detection_rule}
 *       is a post-install verification mechanism, not an install gate.</li>
 *   <li><b>AG-026A evidence missing → BLOCK</b> (not WARN). This is the
 *       last safety gate before AG-027 install execution; missing
 *       evidence cannot be relaxed.</li>
 *   <li><b>Schema version pin</b>: {@code wingetEgress.schemaVersion}
 *       must match {@link WinGetEgressPayloadPolicy#ACCEPTED_SCHEMA_VERSION}.
 *       Any drift → {@code BLOCK winget_egress_schema_unsupported}.</li>
 * </ul>
 *
 * <p>The reason vocabulary is enumerated in {@link Reason} so callers
 * (UI, AG-027) get a stable contract.
 */
@Service
public class EndpointInstallPreflightService {

    /**
     * Inventory freshness threshold for the {@code inventory_stale} WARN
     * code. 24 hours matches the heartbeat / auto-enroll cadence
     * comfortably and gives ops a clear stale-evidence signal without
     * being too noisy for slow-changing fleets.
     */
    public static final Duration INVENTORY_STALE_AFTER = Duration.ofHours(24);

    /**
     * Stable reason vocabulary. Codes are intentionally machine-readable
     * lowercase identifiers (used by UI for i18n keys, by AG-027 for
     * decision recomputation, by audit metadata for queries).
     */
    public enum Reason {
        // BLOCK reasons ──────────────────────────────────────────────
        CATALOG_ITEM_DRAFT("catalog_item_draft"),
        CATALOG_ITEM_REVOKED("catalog_item_revoked"),
        CATALOG_ITEM_DISABLED("catalog_item_disabled"),
        INSTALLER_TYPE_NOT_INSTALL_READY("installer_type_not_install_ready"),
        DEVICE_NOT_ONLINE("device_not_online"),
        DEVICE_DECOMMISSIONED("device_decommissioned"),
        INVENTORY_MISSING("inventory_missing"),
        INVENTORY_UNSUPPORTED("inventory_unsupported"),
        WINGET_NOT_READY("winget_not_ready"),
        WINGET_EGRESS_MISSING("winget_egress_missing"),
        WINGET_EGRESS_UNSUPPORTED("winget_egress_unsupported"),
        WINGET_EGRESS_SCHEMA_UNSUPPORTED("winget_egress_schema_unsupported"),
        WINGET_FIXED_PROBE_PACKAGE_MISMATCH("winget_fixed_probe_package_mismatch"),
        WINGET_PACKAGE_QUERY_NOT_FOUND("winget_package_query_not_found"),

        // WARN reasons (never cause BLOCK on their own) ──────────────
        INVENTORY_STALE("inventory_stale"),
        APPS_UNAVAILABLE("apps_unavailable"),
        INSTALLED_STATE_UNKNOWN("installed_state_unknown"),
        ALREADY_INSTALLED_DIFFERENT_VERSION("already_installed_different_version"),
        WINGET_EGRESS_PARTIAL("winget_egress_partial"),
        WINGET_SOURCE_LIST_WARNING("winget_source_list_warning");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    private final Clock clock;

    public EndpointInstallPreflightService(
            EndpointDeviceRepository deviceRepository,
            EndpointSoftwareCatalogItemRepository catalogRepository,
            EndpointSoftwareInventorySnapshotRepository snapshotRepository,
            Clock clock) {
        this.deviceRepository = deviceRepository;
        this.catalogRepository = catalogRepository;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    /**
     * Public entry — evaluate install preflight for the given device +
     * catalog item slug. Read-only, on-demand compute.
     *
     * @throws ResponseStatusException 404 when the device or the
     *         catalog item is not visible to the caller's tenant.
     */
    @Transactional(readOnly = true)
    public InstallPreflightResponse evaluate(
            AdminTenantContext context, UUID deviceId, String catalogItemId) {
        if (deviceId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "deviceId is required.");
        }
        if (catalogItemId == null || catalogItemId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "catalogItemId query parameter is required.");
        }
        EndpointDevice device = deviceRepository
                .findByTenantIdAndId(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found."));
        EndpointSoftwareCatalogItem catalogItem = catalogRepository
                .findByTenantIdAndCatalogItemId(context.tenantId(), catalogItemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Catalog item not found."));
        Optional<EndpointSoftwareInventorySnapshot> snapshot =
                snapshotRepository.findByTenantIdAndDevice_Id(
                        context.tenantId(), deviceId);
        return compute(device, catalogItem, snapshot.orElse(null));
    }

    /**
     * Pure evaluator — no DB calls. Public for test injection + future
     * reuse by BE-021 (install command creation) which is required by
     * Codex 019e6b88 to recompute the same decision at create time.
     */
    public InstallPreflightResponse compute(
            EndpointDevice device,
            EndpointSoftwareCatalogItem catalogItem,
            EndpointSoftwareInventorySnapshot snapshot) {
        Instant now = Instant.now(clock);
        List<Reason> blocking = new ArrayList<>();
        List<Reason> warnings = new ArrayList<>();

        evaluateCatalogGate(catalogItem, blocking);
        evaluateDeviceGate(device, blocking);
        evaluateInventoryGate(snapshot, blocking, warnings, now);

        // WinGet-provider gates fire only when the catalog item is a
        // WINGET install candidate. Non-WINGET providers are out of
        // scope for BE-021A — catalog enum currently only allows
        // WINGET, so this branch is effectively always taken; the
        // explicit guard documents the boundary.
        if (catalogItem != null
                && catalogItem.getProvider() == CatalogProvider.WINGET) {
            evaluateWinGetGate(catalogItem, snapshot, blocking, warnings);
        }

        InstalledState installedState = computeInstalledState(catalogItem, snapshot, warnings);

        InstallPreflightDecision decision;
        if (!blocking.isEmpty()) {
            decision = InstallPreflightDecision.BLOCK;
        } else if (!warnings.isEmpty()) {
            decision = InstallPreflightDecision.WARN;
        } else {
            decision = InstallPreflightDecision.PASS;
        }

        return new InstallPreflightResponse(
                decision,
                catalogItem == null ? null : catalogItem.getCatalogItemId(),
                catalogItem == null ? null : catalogItem.getId(),
                device == null ? null : device.getId(),
                now,
                installedState,
                buildEvidence(snapshot, catalogItem),
                merge(blocking, warnings),
                codes(blocking),
                codes(warnings),
                requirementsFrom(blocking));
    }

    // ────────────────────────────────────────────────────────────────
    // Gate evaluators

    private void evaluateCatalogGate(
            EndpointSoftwareCatalogItem catalogItem, List<Reason> blocking) {
        if (catalogItem == null) {
            // The public entry has already thrown 404; the pure
            // compute path tolerates null only for tests that exercise
            // the device + inventory branches in isolation.
            return;
        }
        switch (catalogItem.getStatus()) {
            case DRAFT -> blocking.add(Reason.CATALOG_ITEM_DRAFT);
            case REVOKED -> blocking.add(Reason.CATALOG_ITEM_REVOKED);
            case APPROVED -> {
                if (!catalogItem.isEnabled()) {
                    blocking.add(Reason.CATALOG_ITEM_DISABLED);
                }
            }
        }
        // Provider / sourceType / installerType are install-readiness
        // signals; a catalog item created for inventory-only matching
        // can be APPROVED+enabled but still missing the install fields.
        // The first non-WINGET provider would also trip this gate
        // (defence-in-depth against a future enum widening).
        if (catalogItem.getProvider() != CatalogProvider.WINGET
                || catalogItem.getSourceType() != CatalogSourceType.WINGET
                || catalogItem.getInstallerType() != CatalogInstallerType.WINGET_SILENT) {
            blocking.add(Reason.INSTALLER_TYPE_NOT_INSTALL_READY);
        }
    }

    private void evaluateDeviceGate(EndpointDevice device, List<Reason> blocking) {
        if (device == null) {
            return;
        }
        DeviceStatus status = device.getStatus();
        if (status == DeviceStatus.DECOMMISSIONED) {
            blocking.add(Reason.DEVICE_DECOMMISSIONED);
            return;
        }
        if (status != DeviceStatus.ONLINE) {
            // STALE / OFFLINE / PENDING_ENROLLMENT are all
            // "device not currently reachable for install".
            blocking.add(Reason.DEVICE_NOT_ONLINE);
        }
    }

    private void evaluateInventoryGate(
            EndpointSoftwareInventorySnapshot snapshot,
            List<Reason> blocking,
            List<Reason> warnings,
            Instant now) {
        if (snapshot == null) {
            blocking.add(Reason.INVENTORY_MISSING);
            return;
        }
        if (!snapshot.isSupported()) {
            blocking.add(Reason.INVENTORY_UNSUPPORTED);
            return;
        }
        // Codex 019e6ba4 iter-1 absorb (P1#2): freshness is computed
        // against EACH evidence stream's own timestamp, not the bulk
        // updatedAt. A wingetEgress-only ingest refreshes updatedAt
        // and would otherwise mask a 3-day-stale apps_collected_at —
        // which is the exact data the installedState heuristic relies
        // on. Each stale stream contributes a single dedup'd
        // inventory_stale warning.
        boolean stale = false;
        if (isStale(snapshot.getSummaryCollectedAt(), now)) {
            stale = true;
        }
        if (snapshot.isAppsAvailable()
                && isStale(snapshot.getAppsCollectedAt(), now)) {
            stale = true;
        }
        if (snapshot.getWingetEgress() != null
                && isStale(snapshot.getWingetEgressCollectedAt(), now)) {
            stale = true;
        }
        if (stale) {
            warnings.add(Reason.INVENTORY_STALE);
        }
        if (!snapshot.isAppsAvailable()) {
            warnings.add(Reason.APPS_UNAVAILABLE);
        }
    }

    private static boolean isStale(Instant collectedAt, Instant now) {
        return collectedAt != null
                && Duration.between(collectedAt, now)
                        .compareTo(INVENTORY_STALE_AFTER) > 0;
    }

    private void evaluateWinGetGate(
            EndpointSoftwareCatalogItem catalogItem,
            EndpointSoftwareInventorySnapshot snapshot,
            List<Reason> blocking,
            List<Reason> warnings) {
        if (snapshot == null) {
            // Already blocked by INVENTORY_MISSING — do not double-add
            // WinGet-specific codes that would imply a partial-evidence
            // state.
            return;
        }
        Boolean wingetReady = snapshot.getWingetReady();
        if (wingetReady == null || !wingetReady) {
            blocking.add(Reason.WINGET_NOT_READY);
        }
        Map<String, Object> egress = snapshot.getWingetEgress();
        if (egress == null) {
            blocking.add(Reason.WINGET_EGRESS_MISSING);
            return;
        }
        // Codex 019e6ba4 iter-2 absorb (P1): the validator tolerates
        // wingetEgress.supported=false (the non-Windows agent stub
        // legitimately ships it that way), but the service must still
        // BLOCK on it. Without this check a malformed/future agent
        // payload with `supported=false + packageQuery.found=true +
        // empty egress arrays` would have slipped through to PASS —
        // the payload was schema-valid but the WinGet runtime was
        // not on the device.
        Boolean egressSupported = asBool(egress.get("supported"));
        if (!Boolean.TRUE.equals(egressSupported)) {
            blocking.add(Reason.WINGET_EGRESS_UNSUPPORTED);
            return;
        }
        Integer schemaVersion = snapshot.getWingetEgressSchemaVersion();
        if (schemaVersion == null
                || schemaVersion != WinGetEgressPayloadPolicy.ACCEPTED_SCHEMA_VERSION) {
            blocking.add(Reason.WINGET_EGRESS_SCHEMA_UNSUPPORTED);
            return;
        }
        // Codex 019e6b88 P1 absorb — AG-026A probes ONLY 7zip.7zip.
        // A non-pilot catalog item cannot claim PASS via this evidence.
        String pkg = catalogItem == null ? null : catalogItem.getPackageId();
        if (pkg == null || !pkg.equalsIgnoreCase("7zip.7zip")) {
            blocking.add(Reason.WINGET_FIXED_PROBE_PACKAGE_MISMATCH);
            return;
        }
        Map<String, Object> packageQuery = asMap(egress.get("packageQuery"));
        boolean packageFound = packageQuery != null
                && Boolean.TRUE.equals(packageQuery.get("found"));
        if (!packageFound) {
            blocking.add(Reason.WINGET_PACKAGE_QUERY_NOT_FOUND);
        }
        Boolean egressTimeout = asBool(egress.get("timeout"));
        if (Boolean.TRUE.equals(egressTimeout)) {
            warnings.add(Reason.WINGET_EGRESS_PARTIAL);
        }
        String sourceListError = asString(egress.get("sourceListError"));
        if (sourceListError != null && !sourceListError.isBlank()) {
            warnings.add(Reason.WINGET_SOURCE_LIST_WARNING);
        }
        if (hasFailedNetworkCheck(asList(asMap(egress.get("egress")), "dns"))
                || hasFailedNetworkCheck(asList(asMap(egress.get("egress")), "tcp"))
                || hasFailedNetworkCheck(asList(asMap(egress.get("egress")), "https"))) {
            if (!warnings.contains(Reason.WINGET_EGRESS_PARTIAL)) {
                warnings.add(Reason.WINGET_EGRESS_PARTIAL);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // installedState

    private InstalledState computeInstalledState(
            EndpointSoftwareCatalogItem catalogItem,
            EndpointSoftwareInventorySnapshot snapshot,
            List<Reason> warnings) {
        if (snapshot == null || !snapshot.isAppsAvailable()) {
            warnings.add(Reason.INSTALLED_STATE_UNKNOWN);
            return InstalledState.UNKNOWN;
        }
        if (catalogItem == null) {
            return InstalledState.UNKNOWN;
        }
        // MVP heuristic: only WINGET items currently have a useful
        // inventory match — the inventory items table carries
        // display_name + publisher and AG-026A probes catalog
        // packageId. For non-WINGET providers (and for FILE_EXISTS /
        // FILE_SHA256 detection rules where the inventory lacks file
        // metadata) we surface UNKNOWN so the operator does not see
        // a false NOT_INSTALLED that would trigger an unwanted
        // re-install prompt.
        if (catalogItem.getProvider() != CatalogProvider.WINGET) {
            warnings.add(Reason.INSTALLED_STATE_UNKNOWN);
            return InstalledState.UNKNOWN;
        }
        String pkg = catalogItem.getPackageId();
        String displayName = catalogItem.getDisplayName();
        if ((pkg == null || pkg.isBlank())
                && (displayName == null || displayName.isBlank())) {
            warnings.add(Reason.INSTALLED_STATE_UNKNOWN);
            return InstalledState.UNKNOWN;
        }
        for (EndpointSoftwareInventoryItem item : snapshot.getItems()) {
            if (matches(pkg, item) || matches(displayName, item)) {
                return InstalledState.INSTALLED;
            }
        }
        return InstalledState.NOT_INSTALLED;
    }

    private static boolean matches(String needle, EndpointSoftwareInventoryItem item) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        String name = item.getDisplayName() == null
                ? "" : item.getDisplayName().toLowerCase(Locale.ROOT);
        return !name.isEmpty() && name.contains(n);
    }

    // ────────────────────────────────────────────────────────────────
    // Evidence + reason shaping helpers

    private InstallPreflightEvidence buildEvidence(
            EndpointSoftwareInventorySnapshot snapshot,
            EndpointSoftwareCatalogItem catalogItem) {
        UUID snapshotId = snapshot == null ? null : snapshot.getId();
        Long snapshotRowVersion = snapshot == null ? null : snapshot.getVersion();
        Instant inventoryUpdatedAt = snapshot == null
                ? null : snapshot.getUpdatedAt();
        Instant summaryCollectedAt = snapshot == null
                ? null : snapshot.getSummaryCollectedAt();
        Instant appsCollectedAt = snapshot == null
                ? null : snapshot.getAppsCollectedAt();
        UUID summaryResultId = snapshot == null
                || snapshot.getLatestSummaryCommandResult() == null
                ? null
                : snapshot.getLatestSummaryCommandResult().getId();
        UUID fullResultId = snapshot == null
                || snapshot.getLatestFullCommandResult() == null
                ? null
                : snapshot.getLatestFullCommandResult().getId();
        UUID egressResultId = snapshot == null
                || snapshot.getLatestWingetEgressCommandResult() == null
                ? null
                : snapshot.getLatestWingetEgressCommandResult().getId();
        Instant egressCollectedAt = snapshot == null
                ? null : snapshot.getWingetEgressCollectedAt();
        Integer egressSchemaVersion = snapshot == null
                ? null : snapshot.getWingetEgressSchemaVersion();
        Long catalogVersion = catalogItem == null ? null : catalogItem.getVersion();
        Instant catalogLastUpdatedAt = catalogItem == null
                ? null : catalogItem.getLastUpdatedAt();
        return new InstallPreflightEvidence(
                snapshotId,
                snapshotRowVersion,
                inventoryUpdatedAt,
                summaryCollectedAt,
                appsCollectedAt,
                summaryResultId,
                fullResultId,
                egressResultId,
                egressCollectedAt,
                egressSchemaVersion,
                catalogVersion,
                catalogLastUpdatedAt);
    }

    private static List<String> merge(List<Reason> blocking, List<Reason> warnings) {
        List<String> all = new ArrayList<>(blocking.size() + warnings.size());
        for (Reason r : blocking) {
            all.add(r.code());
        }
        for (Reason r : warnings) {
            all.add(r.code());
        }
        return List.copyOf(all);
    }

    private static List<String> codes(List<Reason> reasons) {
        List<String> out = new ArrayList<>(reasons.size());
        for (Reason r : reasons) {
            out.add(r.code());
        }
        return List.copyOf(out);
    }

    private static List<String> requirementsFrom(List<Reason> blocking) {
        // Map machine-readable codes to operator-facing requirements.
        // UI may localize these; the codes stay stable.
        List<String> out = new ArrayList<>();
        for (Reason r : blocking) {
            out.add(switch (r) {
                case CATALOG_ITEM_DRAFT -> "Catalog item must be APPROVED + enabled.";
                case CATALOG_ITEM_REVOKED -> "Catalog item has been revoked; create a new approved entry.";
                case CATALOG_ITEM_DISABLED -> "Catalog item is approved but disabled; re-enable to allow install.";
                case INSTALLER_TYPE_NOT_INSTALL_READY -> "Catalog item is not install-ready (provider/sourceType/installerType must be WINGET).";
                case DEVICE_NOT_ONLINE -> "Device must be ONLINE (current status is not ONLINE).";
                case DEVICE_DECOMMISSIONED -> "Device is DECOMMISSIONED; install is not permitted.";
                case INVENTORY_MISSING -> "Run COLLECT_INVENTORY to ingest a software snapshot first.";
                case INVENTORY_UNSUPPORTED -> "Device inventory reported unsupported state (non-Windows or agent stub).";
                case WINGET_NOT_READY -> "WinGet App Installer not ready on the device (wingetReady=false).";
                case WINGET_EGRESS_MISSING -> "Run COLLECT_INVENTORY with includeWinGetEgress=true to capture AG-026A evidence.";
                case WINGET_EGRESS_UNSUPPORTED -> "WinGet egress evidence reports supported=false (likely a non-Windows agent stub).";
                case WINGET_EGRESS_SCHEMA_UNSUPPORTED -> "WinGet egress evidence schema version is not supported by this backend.";
                case WINGET_FIXED_PROBE_PACKAGE_MISMATCH -> "AG-026A only probes the pilot package (7zip.7zip); a per-package probe is required for this catalog item.";
                case WINGET_PACKAGE_QUERY_NOT_FOUND -> "WinGet source could not locate the pilot package on this device.";
                default -> r.code();
            });
        }
        return List.copyOf(out);
    }

    // ────────────────────────────────────────────────────────────────
    // JSON tree helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Map<String, Object> parent, String key) {
        if (parent == null) {
            return List.of();
        }
        Object node = parent.get(key);
        if (node instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object element : iterable) {
                out.add(element);
            }
            return out;
        }
        return List.of();
    }

    private static Boolean asBool(Object node) {
        if (node instanceof Boolean b) {
            return b;
        }
        if (node instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
        }
        return null;
    }

    private static String asString(Object node) {
        return node == null ? null : String.valueOf(node);
    }

    private static boolean hasFailedNetworkCheck(List<Object> checks) {
        if (checks == null || checks.isEmpty()) {
            return false;
        }
        for (Object node : checks) {
            Map<String, Object> m = asMap(node);
            if (m == null) {
                continue;
            }
            Boolean ok = asBool(m.get("ok"));
            if (ok != null && !ok) {
                return true;
            }
        }
        return false;
    }
}
