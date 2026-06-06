package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRevokeRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleSummary;
import com.example.endpointadmin.model.SoftwareBundleStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareBundleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE-029 — approved package bundle admin REST surface.
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-software-bundles")
public class AdminEndpointSoftwareBundleController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final EndpointSoftwareBundleService bundleService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointSoftwareBundleController(
            EndpointSoftwareBundleService bundleService,
            TenantContextResolver tenantContextResolver) {
        this.bundleService = bundleService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public Page<AdminSoftwareBundleSummary> listBundles(
            @RequestParam(required = false) SoftwareBundleStatus status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        int resolvedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int resolvedPage = Math.max(0, page);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        return bundleService.listBundles(context, status, enabled, pageable);
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminSoftwareBundleResponse createBundle(
            @Valid @RequestBody AdminSoftwareBundleRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return bundleService.createBundle(context, request);
    }

    @GetMapping("/{bundleId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public AdminSoftwareBundleResponse getBundle(@PathVariable String bundleId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return bundleService.getBundle(context, bundleId);
    }

    @PostMapping("/{bundleId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminSoftwareBundleResponse approveBundle(
            @PathVariable String bundleId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return bundleService.approveBundle(context, bundleId);
    }

    @PostMapping("/{bundleId}/revoke")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminSoftwareBundleResponse revokeBundle(
            @PathVariable String bundleId,
            @Valid @RequestBody AdminSoftwareBundleRevokeRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return bundleService.revokeBundle(context, bundleId, request);
    }
}
