package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRequest;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseResponse;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRevokeRequest;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseSummary;
import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAgentUpdateReleaseService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE-031 — signed agent update release catalog admin REST surface.
 *
 * <p>Metadata/control-plane only: no UPDATE_AGENT dispatch and no device
 * targeting in this controller.
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-agent-update-releases")
public class AdminEndpointAgentUpdateReleaseController {

    private static final int MAX_PAGE_SIZE = 200;

    private final EndpointAgentUpdateReleaseService releaseService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointAgentUpdateReleaseController(
            EndpointAgentUpdateReleaseService releaseService,
            TenantContextResolver tenantContextResolver) {
        this.releaseService = releaseService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public Page<AdminAgentUpdateReleaseSummary> listReleases(
            @RequestParam(required = false) AgentUpdateChannel channel,
            @RequestParam(required = false) AgentUpdateReleaseStatus status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        int resolvedPage = Math.max(0, page);
        int resolvedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        return releaseService.listReleases(
                context, channel, status, enabled, pageable);
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminAgentUpdateReleaseResponse createRelease(
            @Valid @RequestBody AdminAgentUpdateReleaseRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return releaseService.createRelease(context, request);
    }

    @GetMapping("/{releaseId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public AdminAgentUpdateReleaseResponse getRelease(
            @PathVariable String releaseId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return releaseService.getRelease(context, releaseId);
    }

    @PutMapping("/{releaseId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminAgentUpdateReleaseResponse updateRelease(
            @PathVariable String releaseId,
            @Valid @RequestBody AdminAgentUpdateReleaseRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return releaseService.updateRelease(context, releaseId, request);
    }

    @PostMapping("/{releaseId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminAgentUpdateReleaseResponse approveRelease(
            @PathVariable String releaseId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return releaseService.approveRelease(context, releaseId);
    }

    @PostMapping("/{releaseId}/revoke")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminAgentUpdateReleaseResponse revokeRelease(
            @PathVariable String releaseId,
            @Valid @RequestBody AdminAgentUpdateReleaseRevokeRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return releaseService.revokeRelease(context, releaseId, request);
    }
}
