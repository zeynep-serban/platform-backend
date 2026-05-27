package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointInstallPreflightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BE-021A — Install Preflight admin REST surface (Faz 22.5).
 *
 * <p>One route, read-only:
 *
 * <pre>
 * GET /api/v1/admin/endpoint-devices/{deviceId}/install-preflight
 *     ?catalogItemId={slug}
 * </pre>
 *
 * <p>Returns the canonical {@link InstallPreflightResponse} (PASS /
 * WARN / BLOCK + reasons + evidence refs). The decision is computed
 * on-demand from the BE-020 catalog row, the BE-020I inventory
 * snapshot, and the AG-026A wingetEgress evidence; no decision row is
 * persisted (Codex 019e6b88 plan-time AGREE).
 *
 * <p>HTTP semantics:
 *
 * <ul>
 *   <li>{@code 200} + body — for every business-evaluable input
 *       (PASS / WARN / BLOCK). Including BLOCK states like
 *       {@code catalog_item_draft}, {@code device_not_online},
 *       {@code inventory_missing}. The response carries the
 *       reason codes; the operator UI translates them.</li>
 *   <li>{@code 400} — invalid query shape (e.g. missing
 *       {@code catalogItemId}; thrown by the service layer).</li>
 *   <li>{@code 404} — device or catalog item not visible to the
 *       caller's tenant.</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin} {@code can_view} — read-only
 * preview. {@code PASS} does NOT authorize an install; that decision
 * lives with the future install-command POST (BE-021 / AG-027) which
 * must recompute the same preflight at command-creation time using
 * the evidence refs in this response.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminInstallPreflightController {

    private final EndpointInstallPreflightService preflightService;
    private final TenantContextResolver tenantContextResolver;

    public AdminInstallPreflightController(
            EndpointInstallPreflightService preflightService,
            TenantContextResolver tenantContextResolver) {
        this.preflightService = preflightService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/install-preflight")
    @RequireModule(
            value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public InstallPreflightResponse evaluate(
            @PathVariable UUID deviceId,
            @RequestParam(name = "catalogItemId") String catalogItemId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return preflightService.evaluate(context, deviceId, catalogItemId);
    }
}
