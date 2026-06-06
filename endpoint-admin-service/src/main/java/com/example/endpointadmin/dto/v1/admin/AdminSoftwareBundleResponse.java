package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareBundle;
import com.example.endpointadmin.model.EndpointSoftwareBundleItem;
import com.example.endpointadmin.model.SoftwareBundleStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminSoftwareBundleResponse(
        UUID id,
        UUID tenantId,
        String bundleId,
        String displayName,
        String description,
        SoftwareBundleStatus status,
        boolean enabled,
        int itemCount,
        List<AdminSoftwareBundleItemResponse> items,
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

    public static AdminSoftwareBundleResponse from(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareBundleItem> items) {
        return new AdminSoftwareBundleResponse(
                bundle.getId(),
                bundle.getTenantId(),
                bundle.getBundleId(),
                bundle.getDisplayName(),
                bundle.getDescription(),
                bundle.getStatus(),
                bundle.isEnabled(),
                items.size(),
                items.stream()
                        .map(AdminSoftwareBundleItemResponse::from)
                        .toList(),
                bundle.getCreatedBySubject(),
                bundle.getCreatedAt(),
                bundle.getLastUpdatedBySubject(),
                bundle.getLastUpdatedAt(),
                bundle.getApprovedBySubject(),
                bundle.getApprovedAt(),
                bundle.getRevokedBySubject(),
                bundle.getRevokedAt(),
                bundle.getRevocationReason()
        );
    }
}
