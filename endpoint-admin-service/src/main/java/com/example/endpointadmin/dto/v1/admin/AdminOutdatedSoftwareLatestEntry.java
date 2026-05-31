package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — flat per-device latest outdated-software entry for the fleet-wide
 * bulk snapshots endpoint (Faz 22.5, #1146). Scalar summary ONLY — NO
 * child {@code packages[]} array (no lazy walk across a fleet-wide
 * fetch), parity with {@link AdminDeviceHealthLatestEntry}.
 *
 * <p>{@code possiblyTruncated} is computed server-side with the SAME
 * fail-closed rule the per-device summary
 * ({@link AdminOutdatedSoftwareSnapshotSummaryResponse}) uses, widened
 * from {@code ==} to {@code >=} so an above-cap aggregate count can never
 * fail-open the hint:
 * {@code upgradeTruncated==TRUE || (upgradeCount!=null && maxUpgrade!=null
 * && upgradeCount >= maxUpgrade)}. The web column builder OR-derives the
 * same signal from {@code upgradeCount}/{@code maxUpgrade}, so the bulk
 * path and the per-device path agree.
 */
public record AdminOutdatedSoftwareLatestEntry(
        UUID deviceId,
        Boolean supported,
        Boolean probeComplete,
        Integer upgradeCount,
        Boolean upgradeTruncated,
        Integer maxUpgrade,
        Boolean possiblyTruncated,
        Instant collectedAt) {

    /**
     * Map from the entity reading ONLY scalar getters. MUST NOT call
     * {@code getPackages()} / {@code getProbeErrors()} — no-child-access
     * invariant (see {@link AdminDeviceHealthLatestEntry#from}).
     */
    public static AdminOutdatedSoftwareLatestEntry from(EndpointOutdatedSoftwareSnapshot s) {
        boolean possiblyTruncated =
                Boolean.TRUE.equals(s.getUpgradeTruncated())
                        || (s.getUpgradeCount() != null
                                && s.getMaxUpgrade() != null
                                && s.getUpgradeCount() >= s.getMaxUpgrade());
        return new AdminOutdatedSoftwareLatestEntry(
                s.getDeviceId(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getUpgradeCount(),
                s.getUpgradeTruncated(),
                s.getMaxUpgrade(),
                possiblyTruncated,
                s.getCollectedAt());
    }
}
