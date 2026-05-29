package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE — full device-health snapshot response (Faz 22.5, AG-033 query
 * API). Mirrors the BE-022Q {@code AdminHardwareInventorySnapshotResponse}
 * whitelist projection.
 *
 * <p>The raw {@code redactedPayload} jsonb is NOT surfaced (parity with
 * BE-022Q must-fix #3); the DTO exposes only the scalar columns, the
 * bounded {@code probeErrors[]}, and the child {@code disks[]} list.
 *
 * <p>{@code payloadHashSha256} is exposed so the web view can show a
 * change-detection fingerprint without re-fetching the previous snapshot
 * for diffing.
 *
 * <p>{@code probeComplete=false} consumers MUST treat the snapshot as
 * "evidence incomplete" (fail-closed) and never render the zero-values as
 * a healthy device; {@code supported=false} renders a "probe not
 * supported on this device" state.
 */
public record AdminDeviceHealthSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Boolean anyLowDisk,
        Integer fixedDiskCount,
        Boolean fixedDisksTruncated,
        Integer maxFixedDisks,
        Short memoryUsedPercent,
        Boolean memoryHighPressure,
        Integer uptimeDays,
        Long uptimeSeconds,
        Long lastBootEpochSec,
        Boolean longUptimeWarning,
        String sourceUsed,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminDeviceHealthDiskResponse> disks,
        List<AdminDeviceHealthProbeErrorResponse> probeErrors) {

    /**
     * Build the response from a managed entity. The caller MUST be
     * inside an open Hibernate session so the LAZY {@code disks}
     * association can be walked (parity with BE-022Q must-fix #4 —
     * {@code spring.jpa.open-in-view=false} means the controller cannot
     * fold lazily outside a transaction). The controller method runs
     * inside a {@code @Transactional(readOnly = true)} boundary.
     */
    public static AdminDeviceHealthSnapshotResponse from(EndpointDeviceHealthSnapshot snapshot) {
        List<AdminDeviceHealthDiskResponse> diskDtos = new ArrayList<>();
        if (snapshot.getDisks() != null) {
            for (var disk : snapshot.getDisks()) {
                diskDtos.add(AdminDeviceHealthDiskResponse.from(disk));
            }
        }
        List<AdminDeviceHealthProbeErrorResponse> errorDtos = new ArrayList<>();
        if (snapshot.getProbeErrors() != null) {
            for (Map<String, Object> raw : snapshot.getProbeErrors()) {
                errorDtos.add(AdminDeviceHealthProbeErrorResponse.from(raw));
            }
        }
        return new AdminDeviceHealthSnapshotResponse(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getSourceCommandResultId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getAnyLowDisk(),
                snapshot.getFixedDiskCount(),
                snapshot.getFixedDisksTruncated(),
                snapshot.getMaxFixedDisks(),
                snapshot.getMemoryUsedPercent(),
                snapshot.getMemoryHighPressure(),
                snapshot.getUptimeDays(),
                snapshot.getUptimeSeconds(),
                snapshot.getLastBootEpochSec(),
                snapshot.getLongUptimeWarning(),
                snapshot.getSourceUsed(),
                snapshot.getProbeDurationMs(),
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt(),
                diskDtos,
                errorDtos);
    }
}
