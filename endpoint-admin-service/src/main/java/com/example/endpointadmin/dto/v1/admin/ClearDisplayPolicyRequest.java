package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * #508 slice-2b — DELETE (CLEAR) request body for the endpoint display-policy
 * surface. A CLEAR removes the backend-managed screensaver/wallpaper Group-Policy
 * keys; a non-blank {@code reason} is required for the audit + the V58
 * {@code endpoint_display_policy_revisions.reason NOT NULL} contract.
 */
public record ClearDisplayPolicyRequest(
        @NotBlank @Size(max = 512) String reason) {
}
