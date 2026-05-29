package com.example.endpointadmin.event;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — application event emitted when a device-health snapshot is
 * persisted (Faz 22.5, AG-033 ingest). Mirrors the BE-022
 * {@link HardwareInventorySnapshotPersistedEvent} precedent: bounded
 * audit metadata only.
 *
 * <p>Audit-safe by construction: this event carries ONLY the bounded
 * metadata fields listed below. No drive letters, no byte totals, no
 * probe summary text, no redacted payload body. Downstream listeners
 * (BE-016 hash-chain audit) can publish this event upstream without
 * leaking device-health detail.
 *
 * @param tenantId             the tenant the snapshot belongs to
 * @param deviceId             the device that produced the snapshot
 * @param snapshotId           the new snapshot's primary key
 * @param sourceCommandId      originating agent command (nullable for
 *                             manual/test ingest)
 * @param schemaVersion        payload schema version reported by the
 *                             agent
 * @param supported            whether the agent considered the OS
 *                             supported for the device-health probe
 * @param probeComplete        whether the probe completed without any
 *                             probeError (fail-closed signal)
 * @param anyLowDisk           whether any fixed disk crossed the
 *                             low-disk threshold (over the FULL
 *                             pre-truncation enumeration)
 * @param memoryHighPressure   whether the memory high-pressure threshold
 *                             was crossed (nullable when unsupported)
 * @param longUptimeWarning    whether the long-uptime threshold was
 *                             crossed (nullable when unsupported)
 * @param payloadHashSha256    SHA-256 of the sanitized device-health
 *                             payload — change-detection signal
 * @param diskCount            number of child fixed-disk rows persisted
 * @param collectedAt          agent-side collection timestamp
 */
public record DeviceHealthSnapshotPersistedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID snapshotId,
        UUID sourceCommandId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Boolean anyLowDisk,
        Boolean memoryHighPressure,
        Boolean longUptimeWarning,
        String payloadHashSha256,
        int diskCount,
        Instant collectedAt) {
}
