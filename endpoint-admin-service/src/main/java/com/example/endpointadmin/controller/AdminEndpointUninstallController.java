package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallAuditResponse;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminUninstallRequestResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointUninstallService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * AG-028 Phase 1b — dedicated managed-uninstall REST surface
 * (Faz 22.5.6).
 *
 * <pre>
 * POST   /api/v1/admin/endpoint-devices/{deviceId}/uninstalls
 * POST   /api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{requestId}/approve
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{requestId}
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/uninstalls
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/uninstalls/history
 * </pre>
 *
 * <p>Mirror of {@link AdminEndpointInstallController} on the destructive
 * side. The dedicated path is the only legal way to create / approve an
 * UNINSTALL_SOFTWARE command — the generic
 * {@link AdminEndpointCommandController#createCommand} rejects the type
 * with 422 (Codex Phase 1 plan-time iter-2 absorb — migrates
 * INSTALL_SOFTWARE from 409 to 422 as part of the
 * {@code DEDICATED_PATH_ONLY} set).
 *
 * <p>HTTP semantics:
 * <ul>
 *   <li>{@code 201 Created} — propose accepted, request in
 *       PENDING_APPROVAL.</li>
 *   <li>{@code 200 OK} — approve dispatched, request in APPROVED with
 *       commandId set.</li>
 *   <li>{@code 403 Forbidden} — maker-checker violation (approver ==
 *       proposer) via
 *       {@link com.example.endpointadmin.service.EndpointUninstallMakerCheckerViolationException}.</li>
 *   <li>{@code 409 Conflict} — in-flight request exists or
 *       idempotency-key reuse with mismatch / state != PENDING_APPROVAL
 *       at approve.</li>
 *   <li>{@code 422 Unprocessable} — catalog gate fail (not APPROVED /
 *       not uninstall_supported / uninstall_protected), no install
 *       provenance, or agent capability not advertised.</li>
 *   <li>{@code 424 Failed Dependency} — agent heartbeat stale at
 *       approve time (retryable, non-terminal).</li>
 *   <li>{@code 503 Service Unavailable} — feature flag
 *       {@code endpoint-admin.uninstall.enabled} disabled.</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin can_manage} for write paths,
 * {@code can_view} for reads (parity with the install surface).
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-devices/{deviceId}/uninstalls")
public class AdminEndpointUninstallController {

    private final EndpointUninstallService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointUninstallController(EndpointUninstallService service,
                                            TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminUninstallRequestResponse> propose(
            @PathVariable UUID deviceId,
            @Valid @RequestBody AdminUninstallRequestCreate request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        AdminUninstallRequestResponse response =
                service.propose(context, deviceId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{requestId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminUninstallRequestResponse approve(
            @PathVariable UUID deviceId,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) AdminUninstallRequestApproval body) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.approve(context, deviceId, requestId, body);
    }

    @GetMapping("/{requestId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public AdminUninstallRequestResponse get(
            @PathVariable UUID deviceId,
            @PathVariable UUID requestId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.get(context, deviceId, requestId);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public List<AdminUninstallRequestResponse> list(
            @PathVariable UUID deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.listForDevice(context, deviceId, page, size);
    }

    @GetMapping("/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public List<AdminUninstallAuditResponse> history(
            @PathVariable UUID deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.getHistory(context, deviceId, page, size);
    }
}
