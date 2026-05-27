package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-020 — compact list-view projection of an
 * {@link EndpointSoftwareCatalogItem} for the admin list endpoint
 * ({@code GET /api/v1/admin/endpoint-software-catalog}).
 *
 * <p>Avoids returning the full detection rule / version policy / silent args
 * payload on list responses; clients fetch a single item by
 * {@code catalogItemId} for the full {@link AdminCatalogItemResponse}.
 */
public record AdminCatalogItemSummary(
        UUID id,
        String catalogItemId,
        CatalogItemStatus status,
        CatalogProvider provider,
        String packageId,
        String displayName,
        String publisher,
        CatalogRiskTier riskTier,
        boolean enabled,
        Instant lastUpdatedAt
) {

    public static AdminCatalogItemSummary from(EndpointSoftwareCatalogItem item) {
        return new AdminCatalogItemSummary(
                item.getId(),
                item.getCatalogItemId(),
                item.getStatus(),
                item.getProvider(),
                item.getPackageId(),
                item.getDisplayName(),
                item.getPublisher(),
                item.getRiskTier(),
                item.isEnabled(),
                item.getLastUpdatedAt()
        );
    }
}
