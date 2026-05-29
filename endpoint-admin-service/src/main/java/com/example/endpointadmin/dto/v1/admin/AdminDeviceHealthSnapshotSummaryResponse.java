package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — summary projection used by the device-health history list
 * endpoint (Faz 22.5, AG-033 query API). Mirrors the BE-022Q
 * {@code AdminHardwareInventorySnapshotSummaryResponse}.
 *
 * <p>No child {@code disks[]} array so a 50-row page does not amplify
 * into hundreds of disk rows on the wire. The full snapshot (with disks)
 * is fetched via the {@code /latest} endpoint or a future per-snapshot
 * detail route.
 *
 * <p>The summary surfaces the warning booleans + counts so the history
 * accordion can flag at-risk snapshots (low disk / high memory pressure /
 * long uptime / incomplete probe) without loading the child collection.
 */
public record AdminDeviceHealthSnapshotSummaryResponse(
        UUID id,
        UUID deviceId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Boolean anyLowDisk,
        Short memoryUsedPercent,
        Boolean memoryHighPressure,
        Integer uptimeDays,
        Boolean longUptimeWarning,
        String sourceUsed,
        Integer fixedDiskCount,
        Integer diskCount,
        Integer probeErrorCount,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt) {

    public static AdminDeviceHealthSnapshotSummaryResponse from(EndpointDeviceHealthSnapshot snapshot) {
        int diskCount = snapshot.getDisks() != null ? snapshot.getDisks().size() : 0;
        int errorCount = snapshot.getProbeErrors() != null ? snapshot.getProbeErrors().size() : 0;
        return new AdminDeviceHealthSnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getDeviceId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getAnyLowDisk(),
                snapshot.getMemoryUsedPercent(),
                snapshot.getMemoryHighPressure(),
                snapshot.getUptimeDays(),
                snapshot.getLongUptimeWarning(),
                snapshot.getSourceUsed(),
                snapshot.getFixedDiskCount(),
                diskCount,
                errorCount,
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt());
    }
}
