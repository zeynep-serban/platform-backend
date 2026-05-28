package com.example.endpointadmin.service.compliance;

import java.util.UUID;

/**
 * BE-023 — Application event published by
 * {@code EndpointSoftwareInventoryService.ingest(...)} immediately
 * before the outer transaction commits. Subscribed to by
 * {@link ComplianceInventoryEventListener} with phase
 * {@code AFTER_COMMIT} so the compliance re-evaluation only runs once
 * the inventory snapshot row is durably visible.
 *
 * <p>Codex 019e6bbf iter-1: "The event must run after the inventory
 * transaction commits, not inside the ingest transaction." This
 * guarantees the re-eval sees the same row the ingest persisted; if
 * the ingest is rolled back, the event listener never fires.
 */
public record SoftwareInventorySnapshotPersistedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID snapshotId,
        String ingestOutcome) {
}
