package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.WinGetEgressPayloadPolicy;
import com.example.endpointadmin.service.compliance.SoftwareInventorySnapshotPersistedEvent;
import com.example.endpointadmin.service.diff.DiffCacheRefreshRequested;
import com.example.endpointadmin.service.diff.DiffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    private static final Logger log =
            LoggerFactory.getLogger(EndpointSoftwareInventoryService.class);

    private final EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    private final EndpointSoftwareInventoryStateHistoryRepository stateHistoryRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;
    private final WinGetEgressPayloadPolicy winGetEgressPayloadPolicy;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public EndpointSoftwareInventoryService(
            EndpointSoftwareInventorySnapshotRepository snapshotRepository,
            EndpointSoftwareInventoryStateHistoryRepository stateHistoryRepository,
            EndpointAuditService auditService,
            Clock clock,
            WinGetEgressPayloadPolicy winGetEgressPayloadPolicy,
            ApplicationEventPublisher eventPublisher) {
        this.snapshotRepository = snapshotRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.winGetEgressPayloadPolicy = winGetEgressPayloadPolicy;
        this.eventPublisher = eventPublisher;
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

        // BE-021A AG-026A wingetEgress sibling capture: when the agent
        // ships `details.inventory.wingetEgress` (or top-level
        // `details.wingetEgress` flat layout), validate the block via
        // the fail-closed WinGetEgressPayloadPolicy and prepare it for
        // persistence. The egress block can ride alongside an inventory
        // payload OR arrive by itself (the agent supports
        // `includeWinGetEgress=true && includeSoftware=false`); when no
        // inventory block is present the service still upserts the
        // snapshot row so the egress evidence has a place to live.
        Map<String, Object> wingetEgress = extractWingetEgressMap(details);
        if (wingetEgress != null) {
            winGetEgressPayloadPolicy.validate(wingetEgress);
        }

        if (inventory == null && wingetEgress == null) {
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

        // Always refresh summary fields when the agent shipped a software
        // inventory block. wingetEgress-only payloads (rare but supported
        // by the agent's two independent `includeSoftware` /
        // `includeWinGetEgress` opt-in bits) keep prior summary fields.
        if (inventory != null) {
            applySummary(snapshot, inventory, now);
            snapshot.setLatestSummaryCommandResult(result);
        } else if (firstIngest) {
            // First-ingest wingetEgress-only path: the schemaVersion +
            // supported NOT NULL DB columns still need defaults so the
            // row can be inserted. Use the AG-026A egress schema
            // version (always 1 today; agent supports independently)
            // and Supported=true because the agent ran the preflight
            // (Supported=false on non-Windows is handled by the agent
            // and the wingetEgress block would NOT be present in that
            // case — DetectSourceEgress returns the stub).
            snapshot.setSchemaVersion(
                    snapshot.getSchemaVersion() == null
                            ? 1 : snapshot.getSchemaVersion());
            snapshot.setSupported(true);
            snapshot.setSummaryCollectedAt(now);
        }

        // Full apps replacement only when the agent shipped the apps[] key
        // AND the value is an explicit array (Iterable). Codex 019e6ac8 P2
        // absorb: `apps: null` / `apps: "oops"` malformed payloads must
        // not wipe prior items; reject 400 instead. `apps: []` IS a valid
        // explicit "no software" snapshot and replaces items intentionally.
        boolean hasAppsKey = inventory != null && inventory.containsKey("apps");
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
        List<EndpointSoftwareInventoryItem> fullPayloadItems = null;
        if (hasFullPayload) {
            fullPayloadItems = parseItems(snapshot, appsNode);
            snapshot.replaceItems(fullPayloadItems);
            snapshot.setLatestFullCommandResult(result);
            snapshot.setAppsCollectedAt(now);
            snapshot.setAppsStoredCount(fullPayloadItems.size());
            snapshot.setAppsAvailable(true);
        }

        // BE-021A AG-026A wingetEgress materialization. The payload was
        // already schema-validated by WinGetEgressPayloadPolicy above.
        // Persist the full sanitised JSONB blob + collected-at + result
        // FK + schema version. A future agent build that ships a new
        // SourceEgressSchemaVersion will be rejected by the validator
        // before reaching this branch (Codex 019e6b88 risk control —
        // BLOCK winget_egress_schema_unsupported is computed by
        // BE-021A from this stored schemaVersion).
        boolean hasWingetEgress = wingetEgress != null;
        if (hasWingetEgress) {
            // Defensive copy: a downstream call could otherwise mutate the
            // incoming details map and corrupt the persisted JSONB.
            snapshot.setWingetEgress(new LinkedHashMap<>(wingetEgress));
            snapshot.setWingetEgressCollectedAt(now);
            snapshot.setLatestWingetEgressCommandResult(result);
            Integer egressSchema = readInt(wingetEgress, "schemaVersion");
            snapshot.setWingetEgressSchemaVersion(
                    egressSchema != null
                            ? egressSchema
                            : WinGetEgressPayloadPolicy.ACCEPTED_SCHEMA_VERSION);
        }

        snapshotRepository.saveAndFlush(snapshot);

        // BE-024 — append-only software-state history. The BE-020I snapshot
        // above is a single-row upsert that physically deletes the prior
        // items, so it cannot answer "what changed since last collect". We
        // therefore capture the diff-relevant subset of every FULL apps[]
        // payload here, in the SAME transaction (atomic with the snapshot,
        // no async listener — lowest-risk for the BE-020/021 freeze window;
        // Codex 019e75a5 (b) absorb). Summary-only / wingetEgress-only
        // ingests do NOT append (the app state did not change).
        boolean stateHistoryInserted = false;
        if (hasFullPayload) {
            stateHistoryInserted = appendStateHistory(tenantId, device.getId(), result,
                    snapshot.getSchemaVersion(), fullPayloadItems, now);
        }

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
        // BE-021A wingetEgress audit signals — schemaVersion + presence only.
        // The raw JSONB blob is NOT included in audit metadata (Codex
        // 019e6b88 acceptance: ingest audit must not carry raw egress
        // / apps payloads).
        auditMetadata.put("wingetEgressIngested", hasWingetEgress);
        if (hasWingetEgress) {
            auditMetadata.put(
                    "wingetEgressSchemaVersion",
                    snapshot.getWingetEgressSchemaVersion());
        }

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

        // BE-024c v2-c-pre-2-C-A (Codex 019e89a3 iter-3 AGREE) — emit
        // AFTER_COMMIT-scoped DiffCacheRefreshRequested event ONLY when
        // a NEW state-history row was actually inserted (i.e. real
        // append). Duplicate source_command_result_id no-ops (via the
        // appendStateHistory boolean refactor) and summary-only /
        // wingetEgress-only ingests don't change the diff and MUST NOT
        // trigger a refresh. Own event class — NOT piggybacked on the
        // BE-023 compliance event below.
        if (stateHistoryInserted && eventPublisher != null) {
            eventPublisher.publishEvent(new DiffCacheRefreshRequested(
                    tenantId, device.getId(), DiffType.SOFTWARE));
        }

        // BE-023 — emit AFTER_COMMIT-scoped event so the compliance
        // evaluator runs a fresh evaluation once the snapshot row is
        // durably visible. The listener uses REQUIRES_NEW so its
        // transaction is independent of this one; if our ingest rolls
        // back the event is dropped (Spring TransactionalEventListener
        // semantics).
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new SoftwareInventorySnapshotPersistedEvent(
                    tenantId, device.getId(), snapshot.getId(), outcome.name()));
        }

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
    // BE-024 software-state history append

    /**
     * Append one append-only software-state capture for a full apps[]
     * ingest. Idempotent on {@code source_command_result_id} (partial
     * UNIQUE): re-delivering the same command-result no-ops instead of
     * appending a duplicate. The capture stores only the diff-relevant
     * whitelist subset (already sanitized at ingest), never the raw item
     * payload.
     *
     * <p><b>Atomicity (Codex 019e75fe CRITICAL).</b> The append must NOT
     * swallow a broad {@code DataIntegrityViolationException}. Doing so
     * (a) mis-classifies a real non-duplicate V18 violation (a CHECK / FK
     * breach) as a "duplicate" and hides a data bug, and (b) on PostgreSQL
     * leaves the surrounding transaction marked rollback-only, so the later
     * audit/commit stage of {@link #ingest} fails uncontrolled — breaking
     * the snapshot+result+history atomicity claim. Instead the write goes
     * through a native {@code INSERT ... ON CONFLICT
     * (source_command_result_id) WHERE source_command_result_id IS NOT NULL
     * DO NOTHING} (see
     * {@link com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepositoryImpl}):
     * ONLY the partial-unique duplicate is a clean no-op (no exception); a
     * concurrent re-delivery that loses the partial-UNIQUE race is also a
     * no-op. EVERY other constraint / FK / CHECK violation PROPAGATES and
     * rolls back the whole ingest transaction together with the snapshot.
     * The pre-probe below stays as a cheap fast path that avoids the insert
     * round-trip on the common re-delivery; the {@code ON CONFLICT} is the
     * authoritative race-safe guard.
     */
    private boolean appendStateHistory(UUID tenantId,
                                       UUID deviceId,
                                       EndpointCommandResult result,
                                       Integer schemaVersion,
                                       List<EndpointSoftwareInventoryItem> items,
                                       Instant now) {
        UUID sourceResultId = result != null ? result.getId() : null;
        // Idempotency fast-path: skip the insert round-trip if this
        // command-result already produced a capture (agent SUBMIT path may
        // re-deliver). The native ON CONFLICT below is the authoritative
        // race-safe guard for the window between this probe and the insert.
        if (sourceResultId != null
                && stateHistoryRepository
                        .findBySourceCommandResultId(sourceResultId)
                        .isPresent()) {
            return false;
        }

        List<Map<String, Object>> digest = SoftwareInventoryDigest.fromItems(items);

        EndpointSoftwareInventoryStateHistory history =
                new EndpointSoftwareInventoryStateHistory();
        // Faz 21.1 PR2b-ii canonical org_id write (Codex 019e8cc2 Option A).
        history.setTenantId(tenantId);
        history.setOrgId(tenantId);
        history.setDeviceId(deviceId);
        history.setSourceCommandResultId(sourceResultId);
        history.setSchemaVersion(schemaVersion != null ? schemaVersion : 1);
        history.setAppCount(digest.size());
        history.setAppsDigestHash(SoftwareInventoryDigest.digestHash(digest));
        history.setAppsDigest(digest);
        history.setCapturedAt(now);

        // ON CONFLICT DO NOTHING: a duplicate source_command_result_id is a
        // no-op (returns false); any other V18 violation throws and rolls
        // back the whole ingest transaction (no swallow, no rollback-only
        // leak into the audit stage).
        // BE-024c v2-c-pre-2-C-A: returns whether a NEW history row was
        // actually inserted so the caller can gate DiffCacheRefreshRequested
        // event publish on a genuine append (vs duplicate/no-op).
        boolean inserted =
                stateHistoryRepository.insertIfNewSourceCommandResult(history);
        if (!inserted) {
            log.debug("BE-024 software-state history append no-op "
                            + "(duplicate source_command_result_id) tenant={} "
                            + "device={} result={}",
                    tenantId, deviceId, sourceResultId);
        }
        return inserted;
    }

    // ----------------------------------------------------------------
    // Helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInventoryMap(
            Map<String, Object> details) {
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            // Codex 019e6aef iter-1 P1 absorb (BE-020I follow-up): the
            // agent (AG-025/AG-025H) ships its software block under
            // `details.inventory.software.{schemaVersion, apps, ...}`.
            // Without this branch the wrapper layout was rejected and
            // full software ingest never landed. The `software` sub-map
            // takes priority — if it carries recognized keys we ingest it
            // directly so `applySummary` + `parseItems` see the correct
            // shape.
            Object softwareNode = ((Map<String, Object>) inventoryMap).get("software");
            if (softwareNode instanceof Map<?, ?> softwareMap
                    && hasRecognizedSoftwareKey((Map<String, Object>) softwareMap)) {
                return (Map<String, Object>) softwareMap;
            }
            // Codex 019e6ac8 P1 absorb (BE-020I PR): nested `inventory`
            // node must carry at least one recognized software-inventory
            // key OR a `summary` sub-map with one. Older agent payloads
            // ship hostname-only / identity-only blobs under
            // `details.inventory.*` for unrelated COLLECT_INVENTORY uses;
            // those must NOT be ingested as a software snapshot.
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

    /**
     * BE-021A — extract the AG-026A {@code wingetEgress} block from a
     * COLLECT_INVENTORY result payload regardless of the layout the
     * agent shipped. Two recognised positions:
     *
     * <ul>
     *   <li>{@code details.inventory.wingetEgress} — preferred (sibling
     *       to {@code inventory.software}; the agent
     *       {@code internal/inventory.Snapshot.WinGetEgress} field maps
     *       here when {@code IncludeWinGetEgress=true}).</li>
     *   <li>{@code details.wingetEgress} — flat fallback for agent
     *       payload shapes that promote sub-blocks to the top level
     *       (mirrors the BE-020I {@code details.{schemaVersion, apps, ...}}
     *       flat layout tolerance).</li>
     * </ul>
     *
     * <p>Returns {@code null} when neither position carries a block, or
     * when the block is not a map (the latter is a validation failure
     * caller surfaces via {@link WinGetEgressPayloadPolicy}).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractWingetEgressMap(
            Map<String, Object> details) {
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            Object egressNode = ((Map<String, Object>) inventoryMap).get("wingetEgress");
            if (egressNode instanceof Map<?, ?> egressMap) {
                return (Map<String, Object>) egressMap;
            }
            if (egressNode != null) {
                // Caller will reject through the policy validator.
                throw new IllegalArgumentException(
                        "details.inventory.wingetEgress must be an object, got "
                                + egressNode.getClass().getSimpleName());
            }
        }
        Object flat = details.get("wingetEgress");
        if (flat instanceof Map<?, ?> flatMap) {
            return (Map<String, Object>) flatMap;
        }
        if (flat != null) {
            throw new IllegalArgumentException(
                    "details.wingetEgress must be an object, got "
                            + flat.getClass().getSimpleName());
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
