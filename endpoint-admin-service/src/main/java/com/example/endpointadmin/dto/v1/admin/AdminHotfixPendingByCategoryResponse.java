package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPosturePendingCategoryCount;

/**
 * BE — pendingByCategory rollup entry of a hotfix-posture snapshot
 * response (Faz 22.5, AG-037 query API). Preserves the FULL
 * pre-truncation category distribution even when the per-item
 * {@code pendingUpdates} list is capped at 20.
 */
public record AdminHotfixPendingByCategoryResponse(
        String category,
        Integer count) {

    public static AdminHotfixPendingByCategoryResponse from(
            EndpointHotfixPosturePendingCategoryCount cat) {
        return new AdminHotfixPendingByCategoryResponse(
                cat.getCategory(),
                cat.getCnt());
    }
}
