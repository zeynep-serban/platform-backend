package com.example.endpointadmin.model;

/**
 * Provenance / trust level for an {@link EndpointSoftwareCatalogItem}
 * (BE-020, Faz 22.5.3). Separate from {@link CatalogProvider} (installer
 * technology) and {@link CatalogSourceType} (distribution channel).
 *
 * <p>Codex 019e6a3e iter-2 RED on a free-text {@code CUSTOM_APPROVED} —
 * approval should never be a trust-level escape hatch. Only the explicit
 * tiers below are accepted.
 *
 * <p>BE-020 MVP service-layer compatibility matrix:
 * {@link CatalogProvider#WINGET} only accepts
 * {@link #WINGET_COMMUNITY_REVIEWED} or {@link #MICROSOFT_STORE}.
 * Other tiers stay column-valid but service-rejected for the WinGet provider.
 */
public enum CatalogSourceTrust {
    /** WinGet manifest accepted into the community-reviewed source. */
    WINGET_COMMUNITY_REVIEWED,

    /** Microsoft Store published package via the {@code msstore} source. */
    MICROSOFT_STORE,

    /** Vendor-signed artifact pinned to an explicit SHA-256 hash. */
    VENDOR_SIGNED_HASH_PINNED,

    /** Internally signed artifact (signed by a platform-owned cert). */
    INTERNAL_SIGNED
}
