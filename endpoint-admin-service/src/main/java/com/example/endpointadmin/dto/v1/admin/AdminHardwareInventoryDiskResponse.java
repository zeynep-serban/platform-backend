package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHardwareInventoryDisk;

/**
 * BE-022Q — disk facet of a hardware inventory snapshot response
 * (Faz 22.5.2 query API).
 *
 * <p>Whitelist projection of the entity: only fields that match a V13
 * column are emitted (Codex 019e70c1 plan-time must-fix #2). The
 * entity does not carry a {@code fileSystem} column — agents that
 * report a file-system label have it silently dropped during ingest,
 * so exposing the field on the response would be misleading.
 *
 * <p>{@code mediaType} and {@code busType} are projected as the
 * canonical enum name strings (SSD/HDD/NVME/UNKNOWN and
 * SATA/NVME/USB/SCSI/IDE/UNKNOWN) so WEB-013 does not need to know
 * about JPA Enumerated semantics.
 */
public record AdminHardwareInventoryDiskResponse(
        String devicePath,
        String model,
        String mediaType,
        String busType,
        Long capacityBytes,
        Long freeBytes,
        Boolean removable) {

    public static AdminHardwareInventoryDiskResponse from(EndpointHardwareInventoryDisk disk) {
        return new AdminHardwareInventoryDiskResponse(
                disk.getDevicePath(),
                disk.getModel(),
                disk.getMediaType() != null ? disk.getMediaType().name() : null,
                disk.getBusType() != null ? disk.getBusType().name() : null,
                disk.getCapacityBytes(),
                disk.getFreeBytes(),
                disk.getRemovable());
    }
}
