package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-020I — Software Inventory Ingest / Query service (Faz 22.5.3A).
 *
 * <p>Plugged into the agent {@code COLLECT_INVENTORY} result-submit flow by
 * {@link EndpointAgentCommandService}: after the
 * {@link com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy}
 * fail-closed PII check + the parent {@code endpoint_command_results} row
 * is persisted, this service upserts the per-device snapshot and (when the
 * payload ships a full {@code apps[]} array) atomically replaces the items.
 *
 * <p>Three ingest outcomes (each emits a distinct BE-016 hash-chain audit
 * event):
 * <ul>
 *   <li>{@code INGESTED} — first snapshot for the device.</li>
 *   <li>{@code REPLACED} — full {@code apps[]} payload arrived;
 *       prior items wiped, new items inserted, {@code apps_available=true}.</li>
 *   <li>{@code SUMMARY_UPDATED} — summary-only payload (no {@code apps}
 *       key); summary fields refreshed, items preserved.</li>
 * </ul>
 *
 * <p>{@code apps_available} latches to {@code true} after the first full
 * ingest and never flips back on summary-only ingests
 * (Codex 019e6ab2 iter-2 acceptance).
 */
@Service
public class EndpointSoftwareInventoryService {

    public static final String EVENT_INGESTED =
            "ENDPOINT_SOFTWARE_INVENTORY_INGESTED";
    public static final String EVENT_REPLACED =
            "ENDPOINT_SOFTWARE_INVENTORY_REPLACED";
    public static final String EVENT_SUMMARY_UPDATED =
            "ENDPOINT_SOFTWARE_INVENTORY_SUMMARY_UPDATED";

    public static final String ACTION_INGEST = "INGEST_SOFTWARE_INVENTORY";

    public enum IngestOutcome {
        INGESTED, REPLACED, SUMMARY_UPDATED, SKIPPED
    }

    private final EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public EndpointSoftwareInventoryService(
            EndpointSoftwareInventorySnapshotRepository snapshotRepository,
            EndpointAuditService auditService,
            Clock clock) {
        this.snapshotRepository = snapshotRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ----------------------------------------------------------------
    // Ingest

    /**
     * Ingest hook called from
     * {@link EndpointAgentCommandService#submitResult} after the result row
     * is persisted. Caller has already validated the {@code request.details}
     * map through {@code SoftwareInventoryPayloadPolicy} — this method
     * trusts that PII guard has run.
     *
     * <p>{@code details} is the raw {@code request.details} map; this method
     * tolerates two equivalent agent layouts:
     * <ul>
     *   <li>{@code details.inventory.{summary, apps[]}} — preferred</li>
     *   <li>{@code details.{schemaVersion, supported, ..., apps[]}} — flat</li>
     * </ul>
     *
     * @return the kind of update applied; {@link IngestOutcome#SKIPPED}
     *         when the payload contains no recognized inventory shape.
     */
    @Transactional
    public IngestOutcome ingest(EndpointDevice device,
                                EndpointCommand command,
                                EndpointCommandResult result,
                                Map<String, Object> details) {
        if (device == null || result == null || details == null) {
            return IngestOutcome.SKIPPED;
        }
        Map<String, Object> inventory = extractInventoryMap(details);
        if (inventory == null) {
            return IngestOutcome.SKIPPED;
        }

        UUID tenantId = device.getTenantId();
        Instant now = Instant.now(clock);

        Optional<EndpointSoftwareInventorySnapshot> existing =
                snapshotRepository.findByTenantIdAndDevice_Id(
                        tenantId, device.getId());
        boolean firstIngest = existing.isEmpty();
        EndpointSoftwareInventorySnapshot snapshot = existing.orElseGet(() -> {
            EndpointSoftwareInventorySnapshot s =
                    new EndpointSoftwareInventorySnapshot();
            s.setTenantId(tenantId);
            s.setDevice(device);
            return s;
        });

        // Always refresh summary fields when present in the payload.
        applySummary(snapshot, inventory, now);
        snapshot.setLatestSummaryCommandResult(result);

        // Full apps replacement only when the agent shipped the apps[] key
        // AND the value is an explicit array (Iterable). Codex 019e6ac8 P2
        // absorb: `apps: null` / `apps: "oops"` malformed payloads must
        // not wipe prior items; reject 400 instead. `apps: []` IS a valid
        // explicit "no software" snapshot and replaces items intentionally.
        boolean hasAppsKey = inventory.containsKey("apps");
        Object appsNode = hasAppsKey ? inventory.get("apps") : null;
        boolean hasFullPayload = hasAppsKey && appsNode instanceof Iterable<?>;
        if (hasAppsKey && !hasFullPayload) {
            throw new IllegalArgumentException(
                    "Software inventory `apps` field must be an array; "
                            + "received "
                            + (appsNode == null ? "null"
                                    : appsNode.getClass().getSimpleName())
                            + ".");
        }
        if (hasFullPayload) {
            List<EndpointSoftwareInventoryItem> next =
                    parseItems(snapshot, appsNode);
            snapshot.replaceItems(next);
            snapshot.setLatestFullCommandResult(result);
            snapshot.setAppsCollectedAt(now);
            snapshot.setAppsStoredCount(next.size());
            snapshot.setAppsAvailable(true);
        }

        snapshotRepository.saveAndFlush(snapshot);

        IngestOutcome outcome;
        String eventType;
        if (firstIngest) {
            outcome = IngestOutcome.INGESTED;
            eventType = EVENT_INGESTED;
        } else if (hasFullPayload) {
            outcome = IngestOutcome.REPLACED;
            eventType = EVENT_REPLACED;
        } else {
            outcome = IngestOutcome.SUMMARY_UPDATED;
            eventType = EVENT_SUMMARY_UPDATED;
        }

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("deviceId", device.getId().toString());
        if (command != null && command.getId() != null) {
            auditMetadata.put("commandId", command.getId().toString());
        }
        if (result.getId() != null) {
            auditMetadata.put("commandResultId", result.getId().toString());
        }
        auditMetadata.put("schemaVersion", snapshot.getSchemaVersion());
        auditMetadata.put("supported", snapshot.isSupported());
        auditMetadata.put("appCount", snapshot.getAppCount());
        auditMetadata.put("appsStoredCount", snapshot.getAppsStoredCount());
        auditMetadata.put("truncated", snapshot.isTruncated());
        auditMetadata.put("wingetReady", snapshot.getWingetReady());
        auditMetadata.put("fullSnapshot", hasFullPayload);
        auditMetadata.put("appsAvailable", snapshot.isAppsAvailable());

        auditService.record(
                tenantId,
                device,
                command,
                eventType,
                ACTION_INGEST,
                command != null ? command.getIssuedBySubject() : null,
                snapshot.getId().toString(),
                auditMetadata,
                null,
                null);

        return outcome;
    }

    // ----------------------------------------------------------------
    // Query

    @Transactional(readOnly = true)
    public Optional<EndpointSoftwareInventorySnapshot> findDeviceSnapshot(
            AdminTenantContext context, UUID deviceId) {
        return snapshotRepository.findByTenantIdAndDevice_Id(
                context.tenantId(), deviceId);
    }

    @Transactional(readOnly = true)
    public EndpointSoftwareInventorySnapshot requireDeviceSnapshot(
            AdminTenantContext context, UUID deviceId) {
        return findDeviceSnapshot(context, deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Software inventory snapshot not found for device."));
    }

    @Transactional(readOnly = true)
    public Page<EndpointSoftwareInventorySnapshot> pageFleet(
            AdminTenantContext context,
            String softwareName,
            String publisher,
            Boolean wingetReady,
            Boolean truncated,
            Pageable pageable) {
        return snapshotRepository.pageByTenantWithFilters(
                context.tenantId(),
                softwareName,
                publisher,
                wingetReady,
                truncated,
                pageable);
    }

    // ----------------------------------------------------------------
    // Helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInventoryMap(
            Map<String, Object> details) {
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            // Codex 019e6ac8 P1 absorb: nested `inventory` node must also
            // carry at least one recognized software-inventory key OR a
            // `summary` sub-map with one. Older agent payloads ship
            // hostname-only / identity-only blobs under `details.inventory.*`
            // for unrelated COLLECT_INVENTORY uses; those must NOT be
            // ingested as a software snapshot. iter-2 absorb additionally
            // restores the preferred `details.inventory.summary.{...}`
            // wrapper layout that the service-level contract documents.
            if (hasRecognizedSoftwareShape((Map<String, Object>) inventoryMap)) {
                return (Map<String, Object>) inventoryMap;
            }
            return null;
        }
        // Tolerate flat layout: when the agent ships the summary fields at
        // the top level of details (Codex 019e6ab2 iter-2 acceptance: agent
        // wire is flexible). Treat the details map itself as the inventory
        // node only when it has at least one recognized inventory key.
        if (hasRecognizedSoftwareShape(details)) {
            return details;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasRecognizedSoftwareShape(Map<String, Object> node) {
        if (hasRecognizedSoftwareKey(node)) {
            return true;
        }
        Object summary = node.get("summary");
        return summary instanceof Map<?, ?> summaryMap
                && hasRecognizedSoftwareKey((Map<String, Object>) summaryMap);
    }

    private static boolean hasRecognizedSoftwareKey(Map<String, Object> node) {
        return node.containsKey("schemaVersion")
                || node.containsKey("supported")
                || node.containsKey("apps")
                || node.containsKey("appCount")
                || node.containsKey("wingetReady")
                || node.containsKey("wingetVersion")
                || node.containsKey("totalSizeKb");
    }

    @SuppressWarnings("unchecked")
    private static void applySummary(EndpointSoftwareInventorySnapshot snapshot,
                                     Map<String, Object> inventory,
                                     Instant now) {
        Map<String, Object> summary = inventory.get("summary")
                instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : inventory;

        Integer schemaVersion = readInt(summary, "schemaVersion");
        if (schemaVersion != null) {
            snapshot.setSchemaVersion(schemaVersion);
        } else if (snapshot.getSchemaVersion() == null) {
            snapshot.setSchemaVersion(1);
        }
        Boolean supported = readBool(summary, "supported");
        if (supported != null) {
            snapshot.setSupported(supported);
        }
        Integer appCount = readInt(summary, "appCount");
        if (appCount != null) {
            snapshot.setAppCount(appCount);
        }
        Boolean wingetReady = readBool(summary, "wingetReady");
        if (wingetReady != null) {
            snapshot.setWingetReady(wingetReady);
        }
        String wingetVersion = readString(summary, "wingetVersion");
        if (wingetVersion != null) {
            snapshot.setWingetVersion(wingetVersion);
        }
        Long totalSizeKb = readLong(summary, "totalSizeKb");
        if (totalSizeKb != null) {
            snapshot.setTotalSizeKb(totalSizeKb);
        }
        Boolean truncated = readBool(summary, "truncated");
        if (truncated != null) {
            snapshot.setTruncated(truncated);
        }
        Object probeErrors = summary.get("probeErrors");
        if (probeErrors instanceof Map<?, ?> probeMap) {
            snapshot.setProbeErrors(new LinkedHashMap<>(
                    (Map<String, Object>) probeMap));
        }
        snapshot.setSummaryCollectedAt(now);
    }

    @SuppressWarnings("unchecked")
    private static List<EndpointSoftwareInventoryItem> parseItems(
            EndpointSoftwareInventorySnapshot snapshot,
            Object appsNode) {
        List<EndpointSoftwareInventoryItem> items = new ArrayList<>();
        if (!(appsNode instanceof Iterable<?> iterable)) {
            return items;
        }
        for (Object raw : iterable) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> appMap = (Map<String, Object>) rawMap;
            EndpointSoftwareInventoryItem item = new EndpointSoftwareInventoryItem();
            item.setTenantId(snapshot.getTenantId());
            if (snapshot.getDevice() != null) {
                item.setDeviceId(snapshot.getDevice().getId());
            }
            String displayName = readString(appMap, "displayName");
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException(
                        "Software inventory item is missing displayName.");
            }
            item.setDisplayName(displayName);
            item.setDisplayVersion(readString(appMap, "displayVersion"));
            item.setPublisher(readString(appMap, "publisher"));
            item.setInstallDate(readString(appMap, "installDate"));
            item.setEstimatedSizeKb(readLong(appMap, "estimatedSizeKb"));
            item.setArchitecture(readString(appMap, "architecture"));
            item.setInstallSource(parseInstallSource(
                    readString(appMap, "installSource")));
            Boolean uninstallPresent = readBool(appMap, "uninstallStringPresent");
            item.setUninstallStringPresent(
                    uninstallPresent != null && uninstallPresent);
            item.setMsiProductCodeHash(readString(appMap, "msiProductCodeHash"));
            // raw_item: store the normalized allowlist subset only; the PII
            // policy has already rejected any forbidden key/value, so the
            // map is safe to persist.
            item.setRawItem(new LinkedHashMap<>(appMap));
            items.add(item);
        }
        return items;
    }

    private static SoftwareInstallSource parseInstallSource(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Software inventory item is missing installSource.");
        }
        try {
            return SoftwareInstallSource.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported installSource '" + raw
                            + "' (HKLM / HKLM_WOW6432 only).");
        }
    }

    private static String readString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer readInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Long readLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Boolean readBool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String trimmed = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equals(trimmed)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
