package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointUninstallRequest;
import com.example.endpointadmin.model.UninstallRequestState;

import java.time.Instant;
import java.util.UUID;

/**
 * AG-028 Phase 1b — REST projection of an
 * {@code endpoint_uninstall_requests} row (Faz 22.5.6).
 *
 * <p>Returned by:
 * <ul>
 *   <li>{@code POST .../uninstalls} (201 — PENDING_APPROVAL)</li>
 *   <li>{@code POST .../uninstalls/{id}/approve} (200 — APPROVED + commandId set)</li>
 *   <li>{@code GET .../uninstalls/{id}} (200)</li>
 * </ul>
 *
 * <p>The {@code commandId} is {@code null} while the request is
 * PENDING_APPROVAL; populated after the maker-checker approval
 * dispatches the {@code UNINSTALL_SOFTWARE} command.
 */
public record AdminUninstallRequestResponse(
        UUID requestId,
        UUID tenantId,
        UUID deviceId,
        UUID catalogItemId,
        UUID commandId,
        UninstallRequestState state,
        String idempotencyKey,
        String reason,
        String createdBy,
        String approvedBy,
        Instant createdAt,
        Instant stateUpdatedAt) {

    public static AdminUninstallRequestResponse from(EndpointUninstallRequest request) {
        return new AdminUninstallRequestResponse(
                request.getId(),
                request.getTenantId(),
                request.getDeviceId(),
                request.getCatalogItemId(),
                request.getCommandId(),
                request.getState(),
                request.getIdempotencyKey(),
                request.getReason(),
                request.getCreatedBy(),
                request.getApprovedBy(),
                request.getCreatedAt(),
                request.getStateUpdatedAt());
    }
}
