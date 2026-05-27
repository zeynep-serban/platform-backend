package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BE-020 — admin revoke payload for an
 * {@link com.example.endpointadmin.model.EndpointSoftwareCatalogItem}.
 *
 * <p>{@code revocationReason} is mandatory so the catalog audit chain has a
 * non-empty operator narrative on every revoke. The DB CHECK
 * {@code ck_endpoint_software_catalog_items_revocation_pair} additionally
 * ensures {@code revoked_by_subject} + {@code revoked_at} stay populated as
 * a pair.
 */
public record AdminCatalogRevokeRequest(
        @NotBlank
        @Size(max = 512)
        String revocationReason
) {
}
