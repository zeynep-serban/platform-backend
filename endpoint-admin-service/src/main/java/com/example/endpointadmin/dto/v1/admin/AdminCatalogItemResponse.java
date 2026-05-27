package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * BE-020 — admin GET / POST / approve / revoke return body for an
 * {@link EndpointSoftwareCatalogItem}.
 *
 * <p>Fields mirror the entity 1:1; the API never leaks internal DB metadata
 * such as the optimistic-locking {@code version} column (callers refer to
 * items by {@code catalogItemId}, not the internal {@code id}).
 */
public record AdminCatalogItemResponse(
        UUID id,
        UUID tenantId,
        String catalogItemId,
        CatalogItemStatus status,
        CatalogProvider provider,
        CatalogSourceType sourceType,
        String sourceName,
        CatalogSourceTrust sourceTrust,
        String packageId,
        String displayName,
        String publisher,
        CatalogVersionPolicyType versionPolicyType,
        String versionPolicyValue,
        CatalogInstallerType installerType,
        CatalogSilentArgsPolicy silentArgsPolicy,
        String sha256,
        String provenance,
        Map<String, Object> detectionRule,
        CatalogRiskTier riskTier,
        boolean enabled,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant lastUpdatedAt,
        String approvedBySubject,
        Instant approvedAt,
        String revokedBySubject,
        Instant revokedAt,
        String revocationReason
) {

    public static AdminCatalogItemResponse from(EndpointSoftwareCatalogItem item) {
        return new AdminCatalogItemResponse(
                item.getId(),
                item.getTenantId(),
                item.getCatalogItemId(),
                item.getStatus(),
                item.getProvider(),
                item.getSourceType(),
                item.getSourceName(),
                item.getSourceTrust(),
                item.getPackageId(),
                item.getDisplayName(),
                item.getPublisher(),
                item.getVersionPolicyType(),
                item.getVersionPolicyValue(),
                item.getInstallerType(),
                item.getSilentArgsPolicy(),
                item.getSha256(),
                item.getProvenance(),
                item.getDetectionRule(),
                item.getRiskTier(),
                item.isEnabled(),
                item.getCreatedBySubject(),
                item.getCreatedAt(),
                item.getLastUpdatedBySubject(),
                item.getLastUpdatedAt(),
                item.getApprovedBySubject(),
                item.getApprovedAt(),
                item.getRevokedBySubject(),
                item.getRevokedAt(),
                item.getRevocationReason()
        );
    }
}
