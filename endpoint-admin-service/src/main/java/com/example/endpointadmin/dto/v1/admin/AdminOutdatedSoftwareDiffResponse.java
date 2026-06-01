package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BE-024b — latest-vs-previous outdated-software diff for a device
 * (Faz 22.5 P2-A slice-3).
 *
 * <p>Always returned with HTTP 200 (no-existence-leak; mirrors
 * BE-022Q/BE-024/BE-025 discipline). Codex 019e8542 iter-2 absorb:
 * NO_CHANGE = "package-map delta empty" (NOT a hash fast-path) because
 * AG-036 ingest already dedupes byte-identical payloads at write time.
 */
public record AdminOutdatedSoftwareDiffResponse(
        UUID deviceId,
        DiffStatus status,
        UUID fromSnapshotId,
        UUID toSnapshotId,
        Instant fromCollectedAt,
        Instant toCollectedAt,
        Integer fromUpgradeCount,
        Integer toUpgradeCount,
        Boolean fromPossiblyTruncated,
        Boolean toPossiblyTruncated,
        List<AdminOutdatedSoftwareDiffEntryResponse> added,
        List<AdminOutdatedSoftwareDiffEntryResponse> removed,
        List<AdminOutdatedSoftwareDiffEntryResponse> versionChanged,
        List<AdminOutdatedSoftwareDiffEntryResponse> availableVersionBumped
) {

    public enum DiffStatus {
        OK,
        NO_CHANGE,
        INSUFFICIENT_HISTORY,
        NO_HISTORY
    }

    public static AdminOutdatedSoftwareDiffResponse noHistory(UUID deviceId) {
        return new AdminOutdatedSoftwareDiffResponse(
                deviceId, DiffStatus.NO_HISTORY,
                null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    public static AdminOutdatedSoftwareDiffResponse insufficientHistory(
            UUID deviceId,
            UUID toSnapshotId,
            Instant toCollectedAt,
            Integer toUpgradeCount,
            Boolean toPossiblyTruncated) {
        return new AdminOutdatedSoftwareDiffResponse(
                deviceId, DiffStatus.INSUFFICIENT_HISTORY,
                null, toSnapshotId, null, toCollectedAt,
                null, toUpgradeCount, null, toPossiblyTruncated,
                List.of(), List.of(), List.of(), List.of());
    }

    public static AdminOutdatedSoftwareDiffResponse noChange(
            UUID deviceId,
            UUID fromSnapshotId, UUID toSnapshotId,
            Instant fromCollectedAt, Instant toCollectedAt,
            Integer fromUpgradeCount, Integer toUpgradeCount,
            Boolean fromPossiblyTruncated, Boolean toPossiblyTruncated) {
        return new AdminOutdatedSoftwareDiffResponse(
                deviceId, DiffStatus.NO_CHANGE,
                fromSnapshotId, toSnapshotId,
                fromCollectedAt, toCollectedAt,
                fromUpgradeCount, toUpgradeCount,
                fromPossiblyTruncated, toPossiblyTruncated,
                List.of(), List.of(), List.of(), List.of());
    }
}
