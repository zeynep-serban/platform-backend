package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.DeploymentRing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BE-021 — request body for {@code POST /api/v1/admin/endpoint-devices/{deviceId}/installs}.
 *
 * <p>The {@code catalogItemId} is the public slug (matches the BE-020
 * convention); the backend resolves the internal UUID + denormalized
 * package id from the catalog row. Caller-supplied {@code idempotencyKey}
 * is bounded so the canonical {@code admin-install:{deviceId}:{catalogUuid}:{key}}
 * string fits the {@code endpoint_commands.idempotency_key VARCHAR(128)}
 * column (Codex 019e6dfb iter-3 implementation note #3).
 */
public record CreateInstallRequest(
        @NotBlank
        @Size(max = 128)
        String catalogItemId,

        // Codex iter-4 P1-2: canonical idempotency key
        // `admin-install:{deviceId(36)}:{catalogUuid(36)}:{key}` has a
        // fixed prefix of 88 characters. The DB column is VARCHAR(128),
        // so the supplied key must fit in 40 characters; anything
        // longer is SHA-256-prefix-hashed by
        // {@code resolveInstallIdempotencyKey}.
        @Size(max = 40)
        String idempotencyKey,

        @Size(max = 512)
        String reason,

        DeploymentRing requiredDeploymentRing) {

    public CreateInstallRequest(String catalogItemId, String idempotencyKey, String reason) {
        this(catalogItemId, idempotencyKey, reason, null);
    }
}
