package com.example.endpointadmin.model;

/**
 * Allowlist of silent-install argument policies for an
 * {@link EndpointSoftwareCatalogItem} (BE-020, Faz 22.5.3).
 *
 * <p>BE-020 never accepts a free-text command-line string from API callers
 * (Codex 019e6a3e iter-1 + post-impl iter PARTIAL absorb). The future
 * AG-027 install adapter resolves a policy keyword below into the
 * provider-specific silent args. New policies are added by extending this
 * enum + an explicit follow-up review; the migration CHECK constraint
 * mirrors the same allowlist so a service-layer bug cannot quietly widen
 * the surface.
 */
public enum CatalogSilentArgsPolicy {

    /**
     * Use the platform default silent-install switches for this provider
     * + installer type (e.g. {@code winget install --silent --accept-...}).
     */
    DEFAULT,

    /**
     * Use the vendor-recommended silent-install switches captured in the
     * catalog's runbook for this package (still a fixed, audited string —
     * never a free-text command-line value from the caller).
     */
    VENDOR_RECOMMENDED
}
