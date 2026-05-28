package com.example.endpointadmin.model;

/**
 * Per-catalog-item compliance enforcement mode — BE-023 (Faz 22.5).
 *
 * <p>Codex 019e6bbf iter-1 decision: enforcement lives on a separate
 * tenant-scoped policy table ({@link EndpointSoftwareCompliancePolicyItem}),
 * not on the catalog itself, so BE-020 catalog metadata stays orthogonal
 * to BE-023 compliance intent. A missing policy row is treated as
 * {@link #ALLOWED} for backward compatibility.
 */
public enum ComplianceEnforcementMode {

    /** Catalog item MUST be installed; absence drives {@code NON_COMPLIANT}. */
    REQUIRED,

    /** Catalog item is approved but optional; no decision impact. */
    ALLOWED,

    /** Catalog item MUST NOT be installed; presence drives {@code UNAUTHORIZED}. */
    FORBIDDEN
}
