package com.example.endpointadmin.event;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — application event emitted when a hotfix-posture snapshot is
 * persisted (Faz 22.5, AG-037 ingest). Mirrors the AG-036
 * {@link OutdatedSoftwareSnapshotPersistedEvent} precedent: bounded
 * audit metadata only (Codex 019e81fe iter-2 P1.6).
 *
 * <p>Audit-safe by construction: this event carries ONLY the bounded
 * metadata fields listed below — NO raw KB id, NO update description,
 * NO update title, NO probe-summary text, NO redacted payload body.
 * Downstream listeners can publish this event upstream without leaking
 * hotfix-posture detail.
 *
 * <p>Specifically NO {@code severityRollup} (Codex 019e81fe iter-2 P1.6
 * ANSWER): the wire {@code pendingByCategory} carries category counts,
 * not severity counts, and the per-item {@code pendingUpdates} list is
 * capped at 20 — deriving a "full" severity distribution from it would
 * leak false evidence. Backend downstream listeners can compute severity
 * rollups from the persisted {@code pendingUpdates} child table if
 * needed (with explicit understanding of the cap).
 *
 * @param tenantId                  tenant the snapshot belongs to
 * @param deviceId                  device that produced the snapshot
 * @param snapshotId                new snapshot's primary key
 * @param sourceCommandId           originating agent command (nullable
 *                                  for manual/test ingest)
 * @param schemaVersion             payload schema version (current: 1)
 * @param supported                 whether the agent considered the OS
 *                                  supported (false on non-Windows)
 * @param probeComplete             whether the probe completed without
 *                                  any probeError and with real evidence
 * @param installedCount            pre-truncation total installed hotfix
 *                                  count
 * @param installedTruncated        whether the agent flagged the
 *                                  installed list as truncated
 * @param installedPossiblyTruncated server-derived hint per
 *                                  {@link com.example.endpointadmin.service.HotfixPostureSnapshotTruncation#isInstalledPossiblyTruncated}
 * @param pendingTotalCount         pre-truncation total pending update
 *                                  count
 * @param pendingTruncated          whether the agent flagged the
 *                                  pending list as truncated
 * @param pendingPossiblyTruncated  server-derived hint per
 *                                  {@link com.example.endpointadmin.service.HotfixPostureSnapshotTruncation#isPendingPossiblyTruncated}
 * @param installedSourceUsed       installed-list source attribution
 *                                  (wua | getHotfix | none)
 * @param pendingSourceUsed         pending-list source attribution (wua
 *                                  | none)
 * @param healthSourceUsed          agent-health source attribution
 *                                  (service | registry | composite | none)
 * @param payloadHashSha256         SHA-256 of the canonical-form
 *                                  payload — change-detection signal
 * @param installedChildCount       number of installed child rows
 *                                  persisted (post-cap; <= 512)
 * @param pendingChildCount         number of pending child rows
 *                                  persisted (post-cap; <= 20)
 * @param collectedAt               server-derived collection timestamp
 */
public record HotfixPostureSnapshotPersistedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID snapshotId,
        UUID sourceCommandId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer installedCount,
        Boolean installedTruncated,
        Boolean installedPossiblyTruncated,
        Integer pendingTotalCount,
        Boolean pendingTruncated,
        Boolean pendingPossiblyTruncated,
        String installedSourceUsed,
        String pendingSourceUsed,
        String healthSourceUsed,
        String payloadHashSha256,
        int installedChildCount,
        int pendingChildCount,
        Instant collectedAt) {
}
