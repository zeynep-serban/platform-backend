package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDeviceHealthDisk;

/**
 * BE — fixed-disk facet of a device-health snapshot response (Faz 22.5,
 * AG-033 query API). Mirrors the BE-022Q
 * {@code AdminHardwareInventoryDiskResponse} whitelist projection.
 *
 * <p>Redaction boundary: exactly the contract's {@code fixedDiskHealth}
 * shape — {@code driveLetter} ({@code ^[A-Z]:$}), byte totals, derived
 * percent, low-disk warning. NO label / serial / filesystem / mount path
 * / GUID is ever projected because no such column exists on the entity.
 */
public record AdminDeviceHealthDiskResponse(
        String driveLetter,
        Long totalBytes,
        Long freeBytes,
        Short freePercent,
        Boolean lowDiskWarning) {

    public static AdminDeviceHealthDiskResponse from(EndpointDeviceHealthDisk disk) {
        return new AdminDeviceHealthDiskResponse(
                disk.getDriveLetter(),
                disk.getTotalBytes(),
                disk.getFreeBytes(),
                disk.getFreePercent(),
                disk.getLowDiskWarning());
    }
}
