package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;

/**
 * BE — upgradeable-package facet of an outdated-software snapshot response
 * (Faz 22.5, AG-036 query API). Mirrors the AG-033
 * {@code AdminDeviceHealthDiskResponse} whitelist projection.
 *
 * <p>Redaction boundary: EXACTLY the contract's {@code outdatedPackage}
 * shape — {@code packageId} (winget id, no whitespace),
 * {@code installedVersion}, {@code availableVersion}. NO display name /
 * publisher / install location / license / download URL is ever projected
 * because no such column exists on the entity. The JSON key set is the
 * contract set, machine-asserted by the controller slice test.
 */
public record AdminOutdatedSoftwarePackageResponse(
        String packageId,
        String installedVersion,
        String availableVersion) {

    public static AdminOutdatedSoftwarePackageResponse from(EndpointOutdatedSoftwarePackage pkg) {
        return new AdminOutdatedSoftwarePackageResponse(
                pkg.getPackageId(),
                pkg.getInstalledVersion(),
                pkg.getAvailableVersion());
    }
}
