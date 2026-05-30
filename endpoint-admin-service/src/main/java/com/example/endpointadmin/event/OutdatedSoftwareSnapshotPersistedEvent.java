package com.example.endpointadmin.event;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — application event emitted when an outdated-software snapshot is
 * persisted (Faz 22.5, AG-036 ingest). Mirrors the AG-033
 * {@link DeviceHealthSnapshotPersistedEvent} precedent: bounded audit
 * metadata only.
 *
 * <p>Audit-safe by construction: this event carries ONLY the bounded
 * metadata fields listed below. No packageId, no installed/available
 * version strings, no probe summary text, no redacted payload body.
 * Downstream listeners (BE-016 hash-chain audit) can publish this event
 * upstream without leaking outdated-software detail.
 *
 * @param tenantId             the tenant the snapshot belongs to
 * @param deviceId             the device that produced the snapshot
 * @param snapshotId           the new snapshot's primary key
 * @param sourceCommandId      originating agent command (nullable for
 *                             manual/test ingest)
 * @param schemaVersion        payload schema version reported by the agent
 * @param supported            whether the agent considered the OS supported
 *                             for the outdated-software probe
 * @param probeComplete        whether the probe completed without any
 *                             probeError and with a real source (fail-closed
 *                             signal)
 * @param upgradeCount         number of upgradeable packages reported
 * @param upgradeTruncated     whether the agent flagged the upgrade set as
 *                             truncated
 * @param possiblyTruncated    whether {@code upgradeCount == maxUpgrade}
 *                             (the v1 "possibly truncated" signal the agent
 *                             parser cannot self-detect)
 * @param sourceUsed           probe source ({@code winget | none})
 * @param payloadHashSha256    SHA-256 of the sanitized outdated-software
 *                             payload — change-detection signal
 * @param packageCount         number of child package rows persisted
 * @param collectedAt          server-derived collection timestamp
 */
public record OutdatedSoftwareSnapshotPersistedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID snapshotId,
        UUID sourceCommandId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer upgradeCount,
        Boolean upgradeTruncated,
        Boolean possiblyTruncated,
        String sourceUsed,
        String payloadHashSha256,
        int packageCount,
        Instant collectedAt) {
}
