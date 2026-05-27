package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * BE-020I — admin GET projection of an
 * {@link EndpointSoftwareInventorySnapshot} (Faz 22.5.3A).
 *
 * <p>Fields mirror the entity 1:1 except optimistic-lock {@code version} +
 * the {@code latest_*_command_result_id} foreign keys are dropped (the
 * audit chain already cross-references them; the admin surface only needs
 * the summary state). Items are paged separately by the device-detail
 * endpoint.
 */
public record AdminSoftwareInventorySnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        int schemaVersion,
        boolean supported,
        Integer appCount,
        Integer appsStoredCount,
        Boolean wingetReady,
        String wingetVersion,
        Long totalSizeKb,
        boolean truncated,
        Map<String, Object> probeErrors,
        Instant summaryCollectedAt,
        Instant appsCollectedAt,
        boolean appsAvailable,
        Instant updatedAt
) {

    public static AdminSoftwareInventorySnapshotResponse from(
            EndpointSoftwareInventorySnapshot snapshot) {
        return new AdminSoftwareInventorySnapshotResponse(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getDevice() == null
                        ? null : snapshot.getDevice().getId(),
                snapshot.getSchemaVersion() == null
                        ? 0 : snapshot.getSchemaVersion(),
                snapshot.isSupported(),
                snapshot.getAppCount(),
                snapshot.getAppsStoredCount(),
                snapshot.getWingetReady(),
                snapshot.getWingetVersion(),
                snapshot.getTotalSizeKb(),
                snapshot.isTruncated(),
                snapshot.getProbeErrors(),
                snapshot.getSummaryCollectedAt(),
                snapshot.getAppsCollectedAt(),
                snapshot.isAppsAvailable(),
                snapshot.getUpdatedAt()
        );
    }
}
