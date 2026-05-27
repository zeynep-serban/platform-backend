package com.example.endpointadmin.model;

/**
 * Package provider for an {@link EndpointSoftwareCatalogItem} (BE-020, Faz 22.5.3).
 *
 * <p>MVP allowlist: {@link #WINGET} only. MSI / EXE / OS-native installer
 * providers are intentionally out of scope until later sub-faz milestones
 * (22.5.7 install preflight + AG-027). The DB CHECK constraint
 * {@code ck_endpoint_software_catalog_items_provider} is also limited to
 * {@code WINGET} so a service-layer bug cannot quietly widen the allowlist.
 */
public enum CatalogProvider {
    WINGET
}
