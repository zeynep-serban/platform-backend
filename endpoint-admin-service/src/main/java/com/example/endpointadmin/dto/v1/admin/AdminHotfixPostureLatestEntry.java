package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.service.HotfixPostureSnapshotTruncation;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — flat per-device latest hotfix-posture entry for the fleet-wide
 * bulk snapshots endpoint (Faz 22.5, future fleet CSV-export
 * integration). Scalar summary ONLY — NO child arrays (no lazy walk
 * across a fleet-wide fetch), parity with
 * {@link AdminOutdatedSoftwareLatestEntry}.
 *
 * <p>{@code installedPossiblyTruncated} / {@code pendingPossiblyTruncated}
 * are computed server-side via {@link HotfixPostureSnapshotTruncation} —
 * the single source of truth shared with the per-device summary
 * ({@link AdminHotfixPostureSnapshotSummaryResponse}), the full snapshot
 * DTO ({@link AdminHotfixPostureSnapshotResponse}), and the service-
 * level audit event.
 */
public record AdminHotfixPostureLatestEntry(
        UUID deviceId,
        Boolean supported,
        Boolean probeComplete,
        Integer installedCount,
        Boolean installedTruncated,
        Integer maxInstalled,
        Boolean installedPossiblyTruncated,
        Integer pendingTotalCount,
        Boolean pendingTruncated,
        Integer maxPending,
        Boolean pendingPossiblyTruncated,
        String installedSourceUsed,
        String pendingSourceUsed,
        String healthSourceUsed,
        String wuaServiceState,
        String bitsServiceState,
        Instant lastDetectAt,
        Instant lastInstallAt,
        Instant collectedAt) {

    /** Map from the entity reading ONLY scalar getters. MUST NOT call
     *  {@code getInstalledHotfixes()} / {@code getPendingUpdates()} /
     *  {@code getPendingByCategory()} — no-child-access invariant (see
     *  {@link AdminOutdatedSoftwareLatestEntry#from}). */
    public static AdminHotfixPostureLatestEntry from(EndpointHotfixPostureSnapshot s) {
        return new AdminHotfixPostureLatestEntry(
                s.getDeviceId(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getInstalledCount(),
                s.getInstalledTruncated(),
                s.getMaxInstalled(),
                HotfixPostureSnapshotTruncation.isInstalledPossiblyTruncated(s),
                s.getPendingTotalCount(),
                s.getPendingTruncated(),
                s.getMaxPending(),
                HotfixPostureSnapshotTruncation.isPendingPossiblyTruncated(s),
                s.getInstalledSourceUsed(),
                s.getPendingSourceUsed(),
                s.getHealthSourceUsed(),
                s.getWuaServiceState(),
                s.getBitsServiceState(),
                s.getLastDetectAt(),
                s.getLastInstallAt(),
                s.getCollectedAt());
    }
}
