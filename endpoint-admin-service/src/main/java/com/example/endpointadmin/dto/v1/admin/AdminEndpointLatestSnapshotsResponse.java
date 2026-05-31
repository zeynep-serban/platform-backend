package com.example.endpointadmin.dto.v1.admin;

import java.util.List;

/**
 * BE — response for the fleet-wide bulk latest-snapshots endpoint
 * (Faz 22.5, #1146): the latest device-health + latest outdated-software
 * snapshot per device for the caller's tenant, feeding the device
 * inventory CSV-export v2 columns in one round-trip instead of an
 * N-per-row client fetch storm.
 *
 * <h4>Per-group truncation = fail-closed against false-absence</h4>
 *
 * <p>"A device absent from a list ⇒ that device has no snapshot" is an
 * authoritative signal ONLY when that group is NOT truncated. The server
 * caps each group at {@code limit}; when a group has MORE
 * latest-per-device rows than the cap it is returned as an EMPTY list
 * with its {@code *Truncated} flag {@code true}. The consumer then drops
 * that group's columns entirely (rather than reading a partial map as
 * "absent ⇒ none"), so a cap can never masquerade as a real
 * "no snapshot" signal. The two groups truncate INDEPENDENTLY, so a
 * complete group still exports while only the over-cap group is dropped.
 *
 * @param deviceHealth            latest device-health per device (EMPTY when
 *                                {@code deviceHealthTruncated})
 * @param deviceHealthTruncated   true ⇒ the tenant exceeded {@code limit}
 *                                device-health rows; list intentionally empty
 * @param outdatedSoftware        latest outdated-software per device (EMPTY
 *                                when {@code outdatedSoftwareTruncated})
 * @param outdatedSoftwareTruncated true ⇒ over cap; list intentionally empty
 * @param limit                   the per-group cap that was applied
 */
public record AdminEndpointLatestSnapshotsResponse(
        List<AdminDeviceHealthLatestEntry> deviceHealth,
        boolean deviceHealthTruncated,
        List<AdminOutdatedSoftwareLatestEntry> outdatedSoftware,
        boolean outdatedSoftwareTruncated,
        int limit) {
}
