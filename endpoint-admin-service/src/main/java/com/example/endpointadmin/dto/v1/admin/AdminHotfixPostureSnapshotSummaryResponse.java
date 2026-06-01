package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.service.HotfixPostureSnapshotTruncation;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — summary projection used by the hotfix-posture history list
 * endpoint (Faz 22.5, AG-037 query API). Mirrors the AG-036
 * {@link AdminOutdatedSoftwareSnapshotSummaryResponse}.
 *
 * <p>No child {@code installedHotfixes[]}, {@code pendingUpdates[]},
 * {@code pendingByCategory[]} array so a 50-row page does not amplify
 * into hundreds of child rows on the wire. The full snapshot (with
 * children + agent health) is fetched via the {@code /latest}
 * endpoint.
 *
 * <p>The summary surfaces the count + truncation flags + the "possibly
 * truncated" signals so the history accordion can flag at-risk
 * snapshots without loading the child collections.
 */
public record AdminHotfixPostureSnapshotSummaryResponse(
        UUID id,
        UUID deviceId,
        Short schemaVersion,
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
        Integer installedChildCount,
        Integer pendingChildCount,
        Integer probeErrorCount,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt) {

    public static AdminHotfixPostureSnapshotSummaryResponse from(EndpointHotfixPostureSnapshot snapshot) {
        int installedChildCount = snapshot.getInstalledHotfixes() != null
                ? snapshot.getInstalledHotfixes().size() : 0;
        int pendingChildCount = snapshot.getPendingUpdates() != null
                ? snapshot.getPendingUpdates().size() : 0;
        int errorCount = snapshot.getProbeErrors() != null ? snapshot.getProbeErrors().size() : 0;
        return new AdminHotfixPostureSnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getDeviceId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getInstalledCount(),
                snapshot.getInstalledTruncated(),
                snapshot.getMaxInstalled(),
                HotfixPostureSnapshotTruncation.isInstalledPossiblyTruncated(snapshot),
                snapshot.getPendingTotalCount(),
                snapshot.getPendingTruncated(),
                snapshot.getMaxPending(),
                HotfixPostureSnapshotTruncation.isPendingPossiblyTruncated(snapshot),
                snapshot.getInstalledSourceUsed(),
                snapshot.getPendingSourceUsed(),
                snapshot.getHealthSourceUsed(),
                installedChildCount,
                pendingChildCount,
                errorCount,
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt());
    }
}
