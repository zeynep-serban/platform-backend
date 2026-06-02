package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse.DiffStatus;
import java.util.UUID;

/**
 * BE-024c outdated-software diff summary (Faz 22.5 P2-A v2-c-pre, Codex
 * 019e88b5 iter-5 AGREE). Parallel to {@code SoftwareDiffSummary} but for
 * the BE-024b outdated-software diff path.
 *
 * <p>Source ids refer to {@code endpoint_outdated_software_snapshots}
 * (NOT a history table — outdated has no append-only history; the
 * snapshot table itself is the canonical AG-036 source-of-truth) —
 * field names {@code fromSnapshotId}/{@code toSnapshotId} reflect that.
 *
 * <p>Outdated carries a 4th count for {@code availableVersionBumped}
 * (BE-024b — canonical packageId same, installedVersion unchanged,
 * availableVersion changed).
 */
public record OutdatedDiffSummary(
        DiffStatus status,
        UUID fromSnapshotId,
        UUID toSnapshotId,
        int addedCount,
        int removedCount,
        int versionChangedCount,
        int availableVersionBumpedCount
) {

    public static OutdatedDiffSummary noHistory() {
        return new OutdatedDiffSummary(
                DiffStatus.NO_HISTORY, null, null, 0, 0, 0, 0);
    }

    public static OutdatedDiffSummary insufficientHistory(UUID toSnapshotId) {
        return new OutdatedDiffSummary(
                DiffStatus.INSUFFICIENT_HISTORY, null, toSnapshotId, 0, 0, 0, 0);
    }

    public static OutdatedDiffSummary noChange(UUID fromSnapshotId, UUID toSnapshotId) {
        return new OutdatedDiffSummary(
                DiffStatus.NO_CHANGE, fromSnapshotId, toSnapshotId, 0, 0, 0, 0);
    }

    public static OutdatedDiffSummary ok(UUID fromSnapshotId, UUID toSnapshotId,
                                          int addedCount, int removedCount,
                                          int versionChangedCount,
                                          int availableVersionBumpedCount) {
        return new OutdatedDiffSummary(
                DiffStatus.OK, fromSnapshotId, toSnapshotId,
                addedCount, removedCount, versionChangedCount, availableVersionBumpedCount);
    }
}
