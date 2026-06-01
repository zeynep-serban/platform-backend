package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDiagnosticsSnapshotResponse;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDiagnosticsService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BE — agent diagnostics admin REST surface (Faz 22.5, AG-038-be query API).
 * Mirrors the AG-037 {@code AdminEndpointHotfixPostureController}/latest
 * route shape. History deferred per Codex 019e82d7 iter-2 #10.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/diagnostics/latest}
 *       — single most-recent snapshot with flat scalars + lastError triad
 *       + bounded probeErrors[]. {@code 404} when no snapshot exists for
 *       the device, OR when the requesting tenant has no read access
 *       (tenant-scoped repository query — cross-tenant existence does
 *       not leak).</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin} {@code can_view} via
 * {@link RequireModule} (parity with the AG-036 / AG-037 query routes).
 *
 * <p>Lazy loading: {@code @Transactional(readOnly = true)} opens the
 * session for the entire request so the DTO mapper can fold the LAZY
 * probeErrors child collection.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointDiagnosticsController {

    private final EndpointDiagnosticsService diagnosticsService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDiagnosticsController(
            EndpointDiagnosticsService diagnosticsService,
            TenantContextResolver tenantContextResolver) {
        this.diagnosticsService = diagnosticsService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/diagnostics/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminDiagnosticsSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointDiagnosticsSnapshot snapshot = diagnosticsService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No diagnostics snapshot for device " + deviceId));
        return AdminDiagnosticsSnapshotResponse.from(snapshot);
    }
}
