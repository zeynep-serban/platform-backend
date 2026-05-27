package com.example.endpointadmin.model;

/**
 * Catalog item risk tier (BE-020, Faz 22.5.3).
 *
 * <p>Operational signal for the admin UI and future BE-026 rollout ring
 * controls. {@link #LOW} catalog items can be approved with a single review
 * pass; {@link #MED} and {@link #HIGH} items still go through the
 * maker-checker invariant on the {@code approve} endpoint, but the admin UI
 * (WEB-014) can opt to require an explicit risk acknowledgement before the
 * approver call is sent.
 *
 * <p>BE-020 only stores and validates the tier; rollout decisions remain in
 * BE-026 (rings / device tags) and BE-027 (maintenance windows).
 */
public enum CatalogRiskTier {
    LOW,
    MED,
    HIGH
}
