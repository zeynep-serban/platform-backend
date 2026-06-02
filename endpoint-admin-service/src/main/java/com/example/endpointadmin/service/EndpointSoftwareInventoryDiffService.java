package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffEntryResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.service.diff.SoftwareDiffSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-024 — read-only software-inventory diff/history service (Faz 22.5).
 *
 * <p>Computes the per-app delta (added / removed / version-changed) between
 * the latest two retained software-state captures
 * ({@link EndpointSoftwareInventoryStateHistory}) for a device. The diff is
 * computed on-read from the append-only history that
 * {@link EndpointSoftwareInventoryService} writes on each full apps[] ingest
 * — the BE-020I single-row snapshot model itself cannot answer "what
 * changed" because it physically deletes the prior item set on every
 * re-collect.
 *
 * <p>STRICT v1 scope: ADDED / REMOVED / VERSION_CHANGED only. "Outdated /
 * availableVersion" deltas are OUT (BE-024b). No catalog join.
 *
 * <p>Graceful degradation (all HTTP 200, no existence leak — Codex
 * 019e75a5 (d) absorb):
 * <ul>
 *   <li>0 captures → {@code NO_HISTORY} (also the cross-tenant / unknown
 *       device answer; the tenant-scoped query returns nothing either
 *       way).</li>
 *   <li>1 capture → {@code INSUFFICIENT_HISTORY}.</li>
 *   <li>2 captures, identical digest hash → {@code NO_CHANGE}.</li>
 *   <li>2 captures, different → {@code OK} with the populated lists.</li>
 * </ul>
 */
@Service
public class EndpointSoftwareInventoryDiffService {

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("capturedAt"),
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private final EndpointSoftwareInventoryStateHistoryRepository historyRepository;

    public EndpointSoftwareInventoryDiffService(
            EndpointSoftwareInventoryStateHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /**
     * Diff the latest two captures for {@code deviceId} within the tenant.
     */
    @Transactional(readOnly = true)
    public AdminSoftwareInventoryDiffResponse diffLatest(
            AdminTenantContext context, UUID deviceId) {
        List<EndpointSoftwareInventoryStateHistory> latestTwo = historyRepository
                .findByTenantIdAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        context.tenantId(), deviceId, PageRequest.of(0, 2));

        if (latestTwo.isEmpty()) {
            return AdminSoftwareInventoryDiffResponse.noHistory(deviceId);
        }
        EndpointSoftwareInventoryStateHistory latest = latestTwo.get(0);
        if (latestTwo.size() == 1) {
            return AdminSoftwareInventoryDiffResponse.insufficientHistory(
                    deviceId, latest.getCapturedAt(), latest.getAppCount());
        }
        EndpointSoftwareInventoryStateHistory previous = latestTwo.get(1);
        return computeDiff(deviceId, previous, latest);
    }

    /**
     * BE-024c (Codex 019e88b5 iter-5 AGREE) — count-only summary for the
     * BE-024 software diff. Reads the latest two history captures + computes
     * delta counts WITHOUT materializing the added/removed/versionChanged
     * lists; intended for the diff cache write path (hot ingest hook +
     * operator-triggered backfill) where the grid only needs counts and
     * the full lists stay drawer-canonical.
     *
     * <p>Tenant-scoped signature — Codex 019e88b5 iter-3 must_fix #1: cache
     * write path is service-internal so the canonical
     * {@code AdminTenantContext} variant is not needed here; the tenant id
     * arrives from the ingest hook or backfill worker (both already
     * tenant-validated upstream).
     */
    @Transactional(readOnly = true)
    public SoftwareDiffSummary summarize(UUID tenantId, UUID deviceId) {
        List<EndpointSoftwareInventoryStateHistory> latestTwo = historyRepository
                .findByTenantIdAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        tenantId, deviceId, PageRequest.of(0, 2));

        if (latestTwo.isEmpty()) {
            return SoftwareDiffSummary.noHistory();
        }
        EndpointSoftwareInventoryStateHistory latest = latestTwo.get(0);
        if (latestTwo.size() == 1) {
            return SoftwareDiffSummary.insufficientHistory(latest.getId());
        }
        EndpointSoftwareInventoryStateHistory previous = latestTwo.get(1);

        // Fast path: byte-identical re-collect.
        if (previous.getAppsDigestHash() != null
                && previous.getAppsDigestHash().equals(latest.getAppsDigestHash())) {
            return SoftwareDiffSummary.noChange(previous.getId(), latest.getId());
        }

        // Count-only walk — same algorithm as computeDiff() but never
        // materialize the per-app DTOs. The cache row only carries counts;
        // the drawer endpoint keeps the full list path.
        Map<String, Map<String, Object>> prevByKey = indexByAppKey(previous.getAppsDigest());
        Map<String, Map<String, Object>> latestByKey = indexByAppKey(latest.getAppsDigest());

        int addedCount = 0;
        int removedCount = 0;
        int versionChangedCount = 0;
        for (Map.Entry<String, Map<String, Object>> e : latestByKey.entrySet()) {
            Map<String, Object> prevEntry = prevByKey.get(e.getKey());
            if (prevEntry == null) {
                addedCount++;
            } else if (!Objects.equals(
                    str(prevEntry, SoftwareInventoryDigest.KEY_VERSION),
                    str(e.getValue(), SoftwareInventoryDigest.KEY_VERSION))) {
                versionChangedCount++;
            }
        }
        for (String key : prevByKey.keySet()) {
            if (!latestByKey.containsKey(key)) {
                removedCount++;
            }
        }
        // Codex 019e88b5 iter-6 must_fix #1 — drawer parity. The canonical
        // computeDiff() always returns OK once the digest-hash fast path is
        // skipped, even when the per-key walk yields empty lists (e.g. only
        // display_name re-titled — KEY_VERSION unchanged). The summary MUST
        // match that semantics so a future v2-d grid cannot show NO_CHANGE
        // while the drawer endpoint shows OK for the same source pair.
        return SoftwareDiffSummary.ok(
                previous.getId(), latest.getId(),
                addedCount, removedCount, versionChangedCount);
    }

    /**
     * Paged append-only capture history for {@code deviceId} within the
     * tenant. Empty page is the canonical no-data answer (no 404).
     */
    @Transactional(readOnly = true)
    public Page<EndpointSoftwareInventoryStateHistory> history(
            AdminTenantContext context, UUID deviceId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, HISTORY_SORT);
        return historyRepository.findByTenantIdAndDeviceId(
                context.tenantId(), deviceId, pageable);
    }

    // ----------------------------------------------------------------
    // Diff computation (pure — package-visible for unit testing)

    AdminSoftwareInventoryDiffResponse computeDiff(
            UUID deviceId,
            EndpointSoftwareInventoryStateHistory previous,
            EndpointSoftwareInventoryStateHistory latest) {

        // Fast path: byte-identical re-collect → no change. Avoids walking
        // potentially hundreds of apps when nothing moved.
        if (previous.getAppsDigestHash() != null
                && previous.getAppsDigestHash().equals(latest.getAppsDigestHash())) {
            return new AdminSoftwareInventoryDiffResponse(
                    deviceId,
                    AdminSoftwareInventoryDiffResponse.DiffStatus.NO_CHANGE,
                    previous.getCapturedAt(), latest.getCapturedAt(),
                    previous.getAppCount(), latest.getAppCount(),
                    List.of(), List.of(), List.of());
        }

        Map<String, Map<String, Object>> prevByKey = indexByAppKey(previous.getAppsDigest());
        Map<String, Map<String, Object>> latestByKey = indexByAppKey(latest.getAppsDigest());

        List<AdminSoftwareInventoryDiffEntryResponse> added = new ArrayList<>();
        List<AdminSoftwareInventoryDiffEntryResponse> removed = new ArrayList<>();
        List<AdminSoftwareInventoryDiffEntryResponse> versionChanged = new ArrayList<>();

        // ADDED + VERSION_CHANGED: walk the latest set.
        for (Map.Entry<String, Map<String, Object>> e : latestByKey.entrySet()) {
            String appKey = e.getKey();
            Map<String, Object> latestEntry = e.getValue();
            Map<String, Object> prevEntry = prevByKey.get(appKey);
            if (prevEntry == null) {
                added.add(AdminSoftwareInventoryDiffEntryResponse.added(
                        appKey,
                        str(latestEntry, SoftwareInventoryDigest.KEY_DISPLAY_NAME),
                        str(latestEntry, SoftwareInventoryDigest.KEY_PUBLISHER),
                        str(latestEntry, SoftwareInventoryDigest.KEY_VERSION)));
                continue;
            }
            String fromVersion = str(prevEntry, SoftwareInventoryDigest.KEY_VERSION);
            String toVersion = str(latestEntry, SoftwareInventoryDigest.KEY_VERSION);
            if (!Objects.equals(fromVersion, toVersion)) {
                versionChanged.add(AdminSoftwareInventoryDiffEntryResponse.versionChanged(
                        appKey,
                        // Prefer the latest display name/publisher (may have
                        // been re-titled by the vendor between collects).
                        str(latestEntry, SoftwareInventoryDigest.KEY_DISPLAY_NAME),
                        str(latestEntry, SoftwareInventoryDigest.KEY_PUBLISHER),
                        fromVersion, toVersion));
            }
        }

        // REMOVED: walk the previous set for keys absent in the latest.
        for (Map.Entry<String, Map<String, Object>> e : prevByKey.entrySet()) {
            String appKey = e.getKey();
            if (!latestByKey.containsKey(appKey)) {
                Map<String, Object> prevEntry = e.getValue();
                removed.add(AdminSoftwareInventoryDiffEntryResponse.removed(
                        appKey,
                        str(prevEntry, SoftwareInventoryDigest.KEY_DISPLAY_NAME),
                        str(prevEntry, SoftwareInventoryDigest.KEY_PUBLISHER),
                        str(prevEntry, SoftwareInventoryDigest.KEY_VERSION)));
            }
        }

        return new AdminSoftwareInventoryDiffResponse(
                deviceId,
                AdminSoftwareInventoryDiffResponse.DiffStatus.OK,
                previous.getCapturedAt(), latest.getCapturedAt(),
                previous.getAppCount(), latest.getAppCount(),
                added, removed, versionChanged);
    }

    /**
     * Index a stored digest by {@code appKey}. A defensive last-write-wins
     * collapse protects against a (theoretical) duplicate appKey within one
     * capture; the ingest builder sorts + dedupe is implicit, but read-time
     * robustness costs nothing.
     */
    private static Map<String, Map<String, Object>> indexByAppKey(
            List<Map<String, Object>> digest) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (digest == null) {
            return byKey;
        }
        for (Map<String, Object> entry : digest) {
            if (entry == null) {
                continue;
            }
            String appKey = str(entry, SoftwareInventoryDigest.KEY_APP_KEY);
            if (appKey != null) {
                byKey.put(appKey, entry);
            }
        }
        return byKey;
    }

    private static String str(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
