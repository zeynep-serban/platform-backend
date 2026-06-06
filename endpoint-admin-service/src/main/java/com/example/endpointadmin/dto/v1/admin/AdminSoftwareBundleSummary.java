package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareBundle;
import com.example.endpointadmin.model.SoftwareBundleStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminSoftwareBundleSummary(
        UUID id,
        String bundleId,
        String displayName,
        SoftwareBundleStatus status,
        boolean enabled,
        long itemCount,
        Instant lastUpdatedAt
) {

    public static AdminSoftwareBundleSummary from(
            EndpointSoftwareBundle bundle,
            long itemCount) {
        return new AdminSoftwareBundleSummary(
                bundle.getId(),
                bundle.getBundleId(),
                bundle.getDisplayName(),
                bundle.getStatus(),
                bundle.isEnabled(),
                itemCount,
                bundle.getLastUpdatedAt()
        );
    }
}
