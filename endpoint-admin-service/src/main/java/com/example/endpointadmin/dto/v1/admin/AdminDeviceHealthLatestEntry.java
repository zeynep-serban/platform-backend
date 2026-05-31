package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — flat per-device latest device-health entry for the fleet-wide
 * bulk snapshots endpoint (Faz 22.5, #1146). Carries ONLY the scalar
 * summary signals the CSV-export column builder reads — NO child
 * {@code disks[]} array (so the bulk mapper never walks a lazy
 * collection: zero N+1 across a fleet-wide fetch) and NO
 * {@code diskCount}/{@code probeErrorCount} (those require the child
 * collections and the export does not use them).
 *
 * <p>Deliberately distinct from
 * {@link AdminDeviceHealthSnapshotSummaryResponse} (the history
 * accordion projection) PRECISELY because the latter's
 * {@code diskCount}/{@code probeErrorCount} force a child-collection
 * access in its {@code from(...)}; a fleet-wide bulk call must stay
 * strictly scalar so it scales linearly with one query, not N+1.
 */
public record AdminDeviceHealthLatestEntry(
        UUID deviceId,
        Boolean supported,
        Boolean probeComplete,
        Boolean anyLowDisk,
        Short memoryUsedPercent,
        Boolean memoryHighPressure,
        Integer uptimeDays,
        Boolean longUptimeWarning,
        Instant collectedAt) {

    /**
     * Map from the entity reading ONLY scalar getters. MUST NOT call
     * {@code getDisks()} / {@code getProbeErrors()} — the no-child-access
     * invariant is asserted by the bulk repository integration test via
     * Hibernate {@code collectionFetchCount == 0}.
     */
    public static AdminDeviceHealthLatestEntry from(EndpointDeviceHealthSnapshot s) {
        return new AdminDeviceHealthLatestEntry(
                s.getDeviceId(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getAnyLowDisk(),
                s.getMemoryUsedPercent(),
                s.getMemoryHighPressure(),
                s.getUptimeDays(),
                s.getLongUptimeWarning(),
                s.getCollectedAt());
    }
}
