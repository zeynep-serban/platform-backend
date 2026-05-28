package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ComplianceEnforcementMode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * BE-023 — Create / update request body for a compliance policy item.
 *
 * <p>{@code catalogItemId} is the {@link UUID} of the underlying
 * {@code endpoint_software_catalog_items.id} row. Cross-tenant
 * references are physically impossible: the DB enforces the composite
 * FK {@code (catalog_item_id, tenant_id)}; if the caller supplies a
 * catalog id that belongs to a different tenant the INSERT/UPDATE is
 * rejected by the DB before the service touches the row.
 */
public record CompliancePolicyItemRequest(
        @NotNull UUID catalogItemId,
        @NotNull ComplianceEnforcementMode enforcementMode,
        Boolean enabled) {
}
