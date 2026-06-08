package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyResponse;
import com.example.endpointadmin.dto.v1.admin.ClearDisplayPolicyRequest;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDisplayPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * #508 slice-2b — dedicated REST surface for the Endpoint Display Policy
 * (screensaver + wallpaper Group-Policy enforcement).
 *
 * <pre>
 * PUT    /api/v1/admin/endpoint-devices/{deviceId}/display-policy   — propose ENFORCE
 * DELETE /api/v1/admin/endpoint-devices/{deviceId}/display-policy   — propose CLEAR
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/display-policy   — current + open proposal
 * </pre>
 *
 * <p>Always maker-checker: PUT/DELETE create a PENDING {@code SET_DISPLAY_POLICY}
 * command that a SECOND admin approves via the existing dual-control surface
 * ({@code POST /api/v1/admin/.../commands/{id}/approve}); only on APPROVE is the
 * current desired-state promoted. The generic {@code POST /commands} surface
 * rejects {@code SET_DISPLAY_POLICY} with 422 (DEDICATED_PATH_ONLY).
 *
 * <p>HTTP semantics:
 * <ul>
 *   <li>{@code 200 OK} — proposal accepted (PUT/DELETE) or current returned (GET).</li>
 *   <li>{@code 400 Bad Request} — validation failure (invalid .scr / style /
 *       timeout / missing reason / CLEAR sent to PUT).</li>
 *   <li>{@code 404 Not Found} — device not visible, or nothing to clear / GET
 *       with no policy.</li>
 *   <li>{@code 409 Conflict} — a different proposal is already pending approval,
 *       or the device is decommissioned.</li>
 *   <li>{@code 422 Unprocessable} — agent does not advertise the
 *       {@code SET_DISPLAY_POLICY} capability (best-effort early feedback).</li>
 *   <li>{@code 424 Failed Dependency} — agent heartbeat stale (best-effort).</li>
 *   <li>{@code 503 Service Unavailable} — feature flag
 *       {@code endpoint-admin.display-policy.enabled} disabled.</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin can_manage} for writes,
 * {@code can_view} for reads (parity with the uninstall/install surfaces).
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-devices/{deviceId}/display-policy")
public class AdminEndpointDisplayPolicyController {

    private final EndpointDisplayPolicyService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDisplayPolicyController(EndpointDisplayPolicyService service,
                                                TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PutMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public AdminDisplayPolicyResponse enforce(
            @PathVariable UUID deviceId,
            @Valid @RequestBody SetDisplayPolicyRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.enforce(context, deviceId, request);
    }

    @DeleteMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public AdminDisplayPolicyResponse clear(
            @PathVariable UUID deviceId,
            @Valid @RequestBody ClearDisplayPolicyRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.clear(context, deviceId, request);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public AdminDisplayPolicyResponse get(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.get(context, deviceId);
    }
}
