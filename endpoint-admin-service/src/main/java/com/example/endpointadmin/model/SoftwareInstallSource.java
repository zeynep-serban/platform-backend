package com.example.endpointadmin.model;

/**
 * Source registry hive an {@link EndpointSoftwareInventoryItem} was discovered
 * from (BE-020I, Faz 22.5.3A).
 *
 * <p>HKCU / HKEY_USERS per-user expansion is intentionally out of scope —
 * the agent (AG-025) only reads the machine-wide hives and the V8 CHECK
 * constraint mirrors that allowlist so a service-layer bug cannot quietly
 * widen the surface.
 */
public enum SoftwareInstallSource {
    HKLM,
    HKLM_WOW6432
}
