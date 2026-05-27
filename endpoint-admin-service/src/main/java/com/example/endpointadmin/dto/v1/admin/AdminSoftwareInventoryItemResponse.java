package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.SoftwareInstallSource;

import java.util.UUID;

/**
 * BE-020I — admin GET projection of an
 * {@link EndpointSoftwareInventoryItem} (Faz 22.5.3A).
 *
 * <p>Only fields that survived the
 * {@code SoftwareInventoryPayloadPolicy} allowlist are exposed; the raw
 * {@code raw_item} JSONB is left server-side because some keys
 * (e.g. {@code msiProductCodeHash}) the agent ships only as
 * {@code sha256:<16hex>} hashes and aren't meaningful for the admin UI.
 */
public record AdminSoftwareInventoryItemResponse(
        UUID id,
        UUID snapshotId,
        UUID deviceId,
        String displayName,
        String displayVersion,
        String publisher,
        String installDate,
        Long estimatedSizeKb,
        String architecture,
        SoftwareInstallSource installSource,
        boolean uninstallStringPresent,
        String msiProductCodeHash
) {

    public static AdminSoftwareInventoryItemResponse from(
            EndpointSoftwareInventoryItem item) {
        return new AdminSoftwareInventoryItemResponse(
                item.getId(),
                item.getSnapshot() == null ? null : item.getSnapshot().getId(),
                item.getDeviceId(),
                item.getDisplayName(),
                item.getDisplayVersion(),
                item.getPublisher(),
                item.getInstallDate(),
                item.getEstimatedSizeKb(),
                item.getArchitecture(),
                item.getInstallSource(),
                item.isUninstallStringPresent(),
                item.getMsiProductCodeHash()
        );
    }
}
