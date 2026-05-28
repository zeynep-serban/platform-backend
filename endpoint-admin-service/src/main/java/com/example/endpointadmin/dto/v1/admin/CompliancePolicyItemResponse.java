package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ComplianceEnforcementMode;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-023 — Read shape for a compliance policy item.
 *
 * <p>Mirrors {@link com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem}
 * without exposing the JPA managed proxy.
 */
public record CompliancePolicyItemResponse(
        UUID id,
        UUID tenantId,
        UUID catalogItemId,
        String catalogItemKey,
        String catalogDisplayName,
        ComplianceEnforcementMode enforcementMode,
        boolean enabled,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant lastUpdatedAt,
        Long version) {
}
