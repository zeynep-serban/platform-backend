package com.example.endpointadmin.model;

/**
 * Lifecycle status for an approved software bundle (BE-029, Faz 22.5.8).
 *
 * <p>Bundles are control-plane definitions only. A bundle becomes eligible for
 * future rollout policy references only after maker-checker approval; this
 * slice does not dispatch install commands or fan out to devices.
 */
public enum SoftwareBundleStatus {
    DRAFT,
    APPROVED,
    REVOKED
}
