package com.example.endpointadmin.model;

/**
 * Declared installer execution mode for an {@link EndpointSoftwareCatalogItem}
 * (BE-020, Faz 22.5.3). Declarative only at BE-020 — actual execution lands
 * with AG-027 (agent install adapter) once BE-021 + BE-021A gates pass.
 *
 * <p>Nullable on the entity / column: the field is optional for a
 * catalog item that is only ever used for inventory / compliance evaluation
 * and never actually installed by AG-027.
 *
 * <p>BE-020 never accepts a raw silent-args string from API callers; instead
 * the {@code silent_args_policy} column carries one of a small allowlist
 * (e.g. {@code DEFAULT}, {@code VENDOR_RECOMMENDED}) that the future AG-027
 * adapter resolves into provider-specific args.
 */
public enum CatalogInstallerType {
    WINGET_SILENT,
    MSI_SILENT,
    EXE_SILENT
}
