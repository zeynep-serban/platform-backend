package com.example.endpointadmin.dto.compliancegap;

import java.time.Instant;
import java.util.Map;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): single gap detail within a device's
 * compliance gap aggregate.
 *
 * <p>{@code type} is the canonical gap-type enum string (see
 * {@link com.example.endpointadmin.service.ComplianceGapType}). {@code label}
 * is the operator-facing Turkish title.
 *
 * <p>{@code sourceSnapshotCollectedAt} REQUIRED — operator must always know
 * the data freshness behind the gap claim. {@code stale} true when the
 * source snapshot is older than the freshness window.
 *
 * <p>{@code details} is a small allowlisted map of scalar values that
 * explain the gap (e.g. {@code {rdpEnabled: true}}). NEVER contains raw
 * secrets, full paths, or unbounded strings — values pass through the
 * sanitizer policy at source ingest.
 */
public record GapDetail(
        String type,
        String label,
        Instant sourceSnapshotCollectedAt,
        boolean stale,
        Map<String, Object> details
) {
}
