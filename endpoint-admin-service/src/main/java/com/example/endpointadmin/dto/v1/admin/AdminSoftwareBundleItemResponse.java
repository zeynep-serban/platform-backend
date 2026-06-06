package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareBundleItem;

import java.util.UUID;

public record AdminSoftwareBundleItemResponse(
        UUID id,
        int itemOrder,
        boolean required,
        AdminCatalogItemSummary catalogItem
) {

    public static AdminSoftwareBundleItemResponse from(
            EndpointSoftwareBundleItem item) {
        return new AdminSoftwareBundleItemResponse(
                item.getId(),
                item.getItemOrder(),
                item.isRequired(),
                AdminCatalogItemSummary.from(item.getCatalogItem())
        );
    }
}
