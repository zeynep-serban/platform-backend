package com.example.endpointadmin.model;

/**
 * Lifecycle status of an {@link EndpointSoftwareCatalogItem} (BE-020, Faz 22.5.3).
 *
 * <p>Allowed transitions (enforced at the service layer):
 * <pre>
 *   DRAFT     -&gt; APPROVED   (admin approve; maker-checker invariant)
 *   APPROVED  -&gt; REVOKED    (admin revoke; revocation reason required)
 *   DRAFT     -&gt; (no direct revoke; delete or wait for approval)
 *   REVOKED   -&gt; (terminal; new draft required for re-approval)
 * </pre>
 *
 * <p>{@code APPROVED + enabled=true} is the only state eligible for the future
 * AG-027 install adapter / BE-021A preflight lookup. {@code REVOKED} items stay
 * in the catalog for audit history but are never eligible for install.
 */
public enum CatalogItemStatus {
    DRAFT,
    APPROVED,
    REVOKED
}
