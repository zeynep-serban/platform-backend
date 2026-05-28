package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-022Q — summary projection used by the history list endpoint
 * (Faz 22.5.2 query API).
 *
 * <p>No child arrays so a 50-row page does not amplify into ~500
 * disk + ~500 network rows on the wire. WEB-013 fetches the full
 * snapshot on row-click via the per-snapshot detail route (open for
 * BE-022Q v2 backlog) or by re-issuing the {@code /latest} endpoint
 * when the user wants the most recent snapshot.
 *
 * <p>{@code diskCount} / {@code networkInterfaceCount} surface the
 * child cardinality without loading the collections — backed by a
 * count-only query rather than a JOIN FETCH, so the summary endpoint
 * stays predictable on devices with hundreds of disks (a
 * pathological case the partial UNIQUE on
 * {@code source_command_result_id} keeps bounded, but the schema
 * does not prevent).
 */
public record AdminHardwareInventorySnapshotSummaryResponse(
        UUID id,
        UUID deviceId,
        Integer schemaVersion,
        Boolean supported,
        String cpuModel,
        Long ramTotalBytes,
        String osName,
        String osVersion,
        Integer diskCount,
        Integer networkInterfaceCount,
        Integer probeErrorCount,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt) {

    public static AdminHardwareInventorySnapshotSummaryResponse from(EndpointHardwareInventorySnapshot snapshot) {
        int diskCount = snapshot.getDisks() != null ? snapshot.getDisks().size() : 0;
        int nicCount = snapshot.getNetworkInterfaces() != null ? snapshot.getNetworkInterfaces().size() : 0;
        int errorCount = snapshot.getProbeErrors() != null ? snapshot.getProbeErrors().size() : 0;
        return new AdminHardwareInventorySnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getDeviceId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getCpuModel(),
                snapshot.getRamTotalBytes(),
                snapshot.getOsName(),
                snapshot.getOsVersion(),
                diskCount,
                nicCount,
                errorCount,
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt());
    }
}
