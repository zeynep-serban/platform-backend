package com.example.endpointadmin.model;

/**
 * BE-026 rollout ring. The order is intentionally operational, not ordinal:
 * services must compare explicit enum names instead of relying on ordinal
 * progression.
 */
public enum DeploymentRing {
    PILOT,
    IT,
    DEPARTMENT,
    ALL
}
