package com.example.endpointadmin.model;

/**
 * Source distribution channel for an {@link EndpointSoftwareCatalogItem}
 * (BE-020, Faz 22.5.3). Separates the package channel from
 * {@link CatalogProvider} (installer technology) and
 * {@link CatalogSourceTrust} (provenance level).
 *
 * <p>BE-020 MVP service-layer compatibility matrix: {@link CatalogProvider#WINGET}
 * implies {@link #WINGET}. {@link #INTERNAL_ARTIFACT} and
 * {@link #VENDOR_SIGNED_ARTIFACT} stay column-valid for forward-compat with
 * non-WinGet providers but the service-layer rejects them while only WINGET
 * is allowlisted.
 */
public enum CatalogSourceType {
    /** Microsoft WinGet source (msstore / winget / private WinGet REST). */
    WINGET,

    /** Internally hosted artifact (MSI/EXE on platform-controlled storage). */
    INTERNAL_ARTIFACT,

    /** Vendor-signed artifact pulled from a trusted vendor source. */
    VENDOR_SIGNED_ARTIFACT
}
