package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for device lifecycle transitions
 * ({@code POST /api/v1/admin/endpoint-devices/{id}/decommission|reactivate}).
 *
 * <p>{@code reason} is required (KVKK posture + ops audit trail) and bounded to
 * the V56 {@code reason} column length. The service trims and re-validates
 * (defense-in-depth) and the DB enforces {@code btrim(reason) <> ''}.
 */
public record DeviceLifecycleRequest(
        @NotBlank @Size(max = 512) String reason
) {
}
