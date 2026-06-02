package com.example.endpointadmin.dto.compliancegap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): top-level response for
 * {@code GET /api/v1/admin/endpoint-devices/compliance-gap}.
 *
 * <p>{@code items} ordered per {@code sort} param (default {@code lastSeen,desc}).
 * Empty list when no gaps match filters — 200 OK + empty array, not 404.
 *
 * <p>{@code filterEcho} returns the effective normalized filters used by
 * the query — operators inspecting the response can verify their intended
 * scope. {@code computedAt} is server wall-clock at response build.
 *
 * <p>HARD RULE No Fake Work guard: "observed devices only" semantics —
 * the count and items represent devices that have submitted at least one
 * snapshot of a contributing source within the freshness window. Devices
 * with zero snapshots are NOT counted as "compliant"; they are silently
 * out-of-scope. Operators must read the {@code filterEcho.freshnessWindow}
 * to understand the sample.
 */
public record ComplianceGapResponse(
        List<DeviceComplianceGap> items,
        long total,
        int page,
        int pageSize,
        Map<String, Object> filterEcho,
        Instant computedAt
) {
}
