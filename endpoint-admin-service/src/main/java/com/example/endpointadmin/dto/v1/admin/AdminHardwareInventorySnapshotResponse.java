package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE-022Q — full snapshot response (Faz 22.5.2 query API).
 *
 * <p>Whitelist projection of the persisted
 * {@link EndpointHardwareInventorySnapshot} entity — the raw
 * {@code redactedPayload} jsonb is NOT surfaced (Codex 019e70c1
 * plan-time must-fix #3); the DTO exposes only the scalar columns,
 * the bounded {@code probeErrors[]} ({@code code} + {@code summary}),
 * and the child {@code disks[]} / {@code networkInterfaces[]} lists.
 *
 * <p>{@code payloadHashSha256} is exposed so WEB-013 can show a
 * change-detection fingerprint without re-fetching the previous
 * snapshot for diffing.
 */
public record AdminHardwareInventorySnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Integer schemaVersion,
        Boolean supported,
        String cpuModel,
        Short cpuCores,
        Integer cpuFrequencyMhz,
        Long ramTotalBytes,
        Long ramAvailableBytes,
        String osName,
        String osVersion,
        String osKernel,
        String osArch,
        String biosVendor,
        String biosVersion,
        String manufacturer,
        String systemModel,
        Boolean domainJoined,
        String domainName,
        Instant lastBootAt,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminHardwareInventoryDiskResponse> disks,
        List<AdminHardwareInventoryNetworkInterfaceResponse> networkInterfaces,
        List<AdminHardwareInventoryProbeErrorResponse> probeErrors) {

    /**
     * Build the response from a managed entity. The caller MUST be
     * inside an open Hibernate session so the LAZY {@code disks} and
     * {@code networkInterfaces} associations can be walked
     * (Codex 019e70c1 plan-time must-fix #4 —
     * {@code spring.jpa.open-in-view=false} on this service means
     * the controller cannot fold lazily). The service mapping wrapper
     * {@code AdminEndpointHardwareInventoryController.toResponse}
     * runs inside a {@code @Transactional(readOnly = true)} boundary
     * to satisfy this requirement.
     */
    public static AdminHardwareInventorySnapshotResponse from(EndpointHardwareInventorySnapshot snapshot) {
        List<AdminHardwareInventoryDiskResponse> diskDtos = new ArrayList<>();
        if (snapshot.getDisks() != null) {
            for (var disk : snapshot.getDisks()) {
                diskDtos.add(AdminHardwareInventoryDiskResponse.from(disk));
            }
        }
        List<AdminHardwareInventoryNetworkInterfaceResponse> nicDtos = new ArrayList<>();
        if (snapshot.getNetworkInterfaces() != null) {
            for (var nic : snapshot.getNetworkInterfaces()) {
                nicDtos.add(AdminHardwareInventoryNetworkInterfaceResponse.from(nic));
            }
        }
        List<AdminHardwareInventoryProbeErrorResponse> errorDtos = new ArrayList<>();
        if (snapshot.getProbeErrors() != null) {
            for (Map<String, Object> raw : snapshot.getProbeErrors()) {
                errorDtos.add(AdminHardwareInventoryProbeErrorResponse.from(raw));
            }
        }
        return new AdminHardwareInventorySnapshotResponse(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getSourceCommandResultId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getCpuModel(),
                snapshot.getCpuCores(),
                snapshot.getCpuFrequencyMhz(),
                snapshot.getRamTotalBytes(),
                snapshot.getRamAvailableBytes(),
                snapshot.getOsName(),
                snapshot.getOsVersion(),
                snapshot.getOsKernel(),
                snapshot.getOsArch(),
                snapshot.getBiosVendor(),
                snapshot.getBiosVersion(),
                snapshot.getManufacturer(),
                snapshot.getSystemModel(),
                snapshot.getDomainJoined(),
                snapshot.getDomainName(),
                snapshot.getLastBootAt(),
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt(),
                diskDtos,
                nicDtos,
                errorDtos);
    }
}
