package com.example.endpointadmin.model;

/**
 * Trust tier for an agent binary referenced by the release catalog.
 *
 * <p>LAB_ONLY_EVIDENCE remains test/lab proof only. Production self-update
 * dispatch must later require TRUSTED_SIGNED and live signature verification.
 */
public enum AgentUpdateSigningTier {
    TRUSTED_SIGNED,
    LAB_ONLY_EVIDENCE
}
