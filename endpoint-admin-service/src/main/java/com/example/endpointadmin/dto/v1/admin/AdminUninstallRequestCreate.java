package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 1b — request body for
 * {@code POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls}
 * (Faz 22.5.6).
 *
 * <p>The {@code catalogItemId} is the public slug (matches the BE-020
 * convention); the backend resolves the internal UUID + denormalized
 * package id from the catalog row.
 *
 * <p>Caller-supplied {@code idempotencyKey} is bounded so the canonical
 * {@code admin-uninstall:{deviceId(36)}:{catalogUuid(36)}:{key}} string
 * fits the {@code endpoint_uninstall_requests.idempotency_key VARCHAR(128)}
 * column (Codex post-impl iter-1 nit absorb — corrected from earlier
 * {@code endpoint_commands.idempotency_key} mislabel).
 */
public record AdminUninstallRequestCreate(
        @NotBlank
        @Size(max = 128)
        String catalogItemId,

        // Canonical idempotency key
        // `admin-uninstall:{deviceId(36)}:{catalogUuid(36)}:{body}` has a
        // fixed prefix of 90 characters
        // (16 [admin-uninstall:] + 36 [UUID] + 1 [:] + 36 [UUID] + 1 [:]).
        // The DB column is VARCHAR(128), so the body MUST fit 38 chars to
        // keep the canonical string ≤ 128. The @Size(max=40) bound is the
        // DTO ceiling; the service SHA-256-prefix-hashes anything > 38 so
        // a programmatic caller bypassing the DTO still produces a fitting
        // canonical key (parity with the install path).
        @Size(max = 40)
        String idempotencyKey,

        @Size(max = 512)
        String reason) {
}
