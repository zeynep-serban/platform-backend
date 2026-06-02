package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse.DiffStatus;
import java.util.UUID;

/**
 * BE-024c (Faz 22.5 P2-A v2-c-pre, Codex 019e88b5 iter-5 AGREE) — pure
 * summary core for the software-inventory diff path. Mirrors the BE-024
 * canonical {@link DiffStatus} but ONLY carries the count summary —
 * never the full added/removed/versionChanged list payloads the drawer
 * endpoint streams.
 *
 * <p>Two read paths:
 * <ul>
 *   <li>Drawer (canonical) — full list compute via
 *       {@code EndpointSoftwareInventoryDiffService.diffLatest()}.</li>
 *   <li>Cache (this summary) — count-only compute via
 *       {@code EndpointSoftwareInventoryDiffService.summarize()} —
 *       written by the ingest hook + by the operator-triggered backfill;
 *       read back by the v2-d grid SCHEMA v5 (deferred to a separate PR).</li>
 * </ul>
 *
 * <p>Source ids reference history captures (NOT inventory snapshots) —
 * Codex 019e88b5 iter-2 must_fix #1: BE-024 algorithm reads the latest
 * two rows from {@code endpoint_software_inventory_state_history}, not
 * the inventory snapshot table (the latter is a single-row upsert).
 * Field names reflect that source-of-truth correctly.
 *
 * <p>Status / source-id pairing semantics:
 * <ul>
 *   <li>{@link DiffStatus#NO_HISTORY} — zero captures; both ids null.</li>
 *   <li>{@link DiffStatus#INSUFFICIENT_HISTORY} — exactly one capture;
 *       only {@code toHistoryId} set.</li>
 *   <li>{@link DiffStatus#NO_CHANGE} — two captures, identical digest
 *       hash; both ids set; all counts {@code 0}.</li>
 *   <li>{@link DiffStatus#OK} — two captures with deltas; both ids set;
 *       counts populated.</li>
 * </ul>
 */
public record SoftwareDiffSummary(
        DiffStatus status,
        UUID fromHistoryId,
        UUID toHistoryId,
        int addedCount,
        int removedCount,
        int versionChangedCount
) {

    public static SoftwareDiffSummary noHistory() {
        return new SoftwareDiffSummary(DiffStatus.NO_HISTORY, null, null, 0, 0, 0);
    }

    public static SoftwareDiffSummary insufficientHistory(UUID toHistoryId) {
        return new SoftwareDiffSummary(
                DiffStatus.INSUFFICIENT_HISTORY, null, toHistoryId, 0, 0, 0);
    }

    public static SoftwareDiffSummary noChange(UUID fromHistoryId, UUID toHistoryId) {
        return new SoftwareDiffSummary(
                DiffStatus.NO_CHANGE, fromHistoryId, toHistoryId, 0, 0, 0);
    }

    public static SoftwareDiffSummary ok(UUID fromHistoryId, UUID toHistoryId,
                                          int addedCount, int removedCount, int versionChangedCount) {
        return new SoftwareDiffSummary(
                DiffStatus.OK, fromHistoryId, toHistoryId,
                addedCount, removedCount, versionChangedCount);
    }
}
