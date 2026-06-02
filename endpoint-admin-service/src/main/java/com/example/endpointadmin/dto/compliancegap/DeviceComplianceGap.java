package com.example.endpointadmin.dto.compliancegap;

import java.time.Instant;
import java.util.List;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): per-device compliance gap
 * aggregate. One row per device with ≥ 1 active gap.
 *
 * @param deviceId      EndpointDevice UUID.
 * @param deviceName    {@code hostname} (or {@code display_name} fallback).
 * @param lastSeen      Most recent {@code collected_at} across all
 *                      contributing snapshots (max).
 * @param gapCount      Number of distinct gap types active for this device.
 * @param gapStrength   {@code strong} (all snapshots within freshness window)
 *                      or {@code weak} (≥ 1 stale snapshot).
 * @param gaps          Ordered list of gap details.
 * @param staleComponents Non-empty when gapStrength=weak — names of source
 *                      snapshot tables that were stale.
 */
public record DeviceComplianceGap(
        String deviceId,
        String deviceName,
        Instant lastSeen,
        int gapCount,
        String gapStrength,
        List<GapDetail> gaps,
        List<String> staleComponents
) {
}
