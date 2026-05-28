package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.ComplianceEvaluationListResponse;
import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemRequest;
import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.EndpointCompliancePolicyService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BE-023 — Compliance policy CRUD admin REST surface (Faz 22.5).
 *
 * <pre>
 * GET    /api/v1/admin/compliance/policy-items?page=&amp;size=   — list (can_view)
 * GET    /api/v1/admin/compliance/policy-items/{id}            — single (can_view)
 * POST   /api/v1/admin/compliance/policy-items                 — create (can_manage)
 * PUT    /api/v1/admin/compliance/policy-items/{id}            — update (can_manage)
 * DELETE /api/v1/admin/compliance/policy-items/{id}            — delete (can_manage)
 * </pre>
 *
 * <p>Composite-FK tenant integrity is enforced at the DB layer; the
 * service additionally pre-checks the catalog item exists in the
 * caller's tenant so the caller gets a clean 400 (not a 500) on a
 * cross-tenant attempt.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance/policy-items")
public class AdminCompliancePolicyController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final EndpointCompliancePolicyService policyService;
    private final TenantContextResolver tenantContextResolver;

    public AdminCompliancePolicyController(
            EndpointCompliancePolicyService policyService,
            TenantContextResolver tenantContextResolver) {
        this.policyService = policyService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ComplianceEvaluationListResponse<CompliancePolicyItemResponse> list(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        Pageable pageable = clampPageable(page, size,
                Sort.by(Sort.Direction.DESC, "lastUpdatedAt"));
        Page<CompliancePolicyItemResponse> result = policyService.list(tenant, pageable);
        return new ComplianceEvaluationListResponse<>(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public CompliancePolicyItemResponse get(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return policyService.get(tenant, id);
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<CompliancePolicyItemResponse> create(
            @Valid @RequestBody CompliancePolicyItemRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        CompliancePolicyItemResponse created = policyService.create(tenant, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public CompliancePolicyItemResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody CompliancePolicyItemRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return policyService.update(tenant, id, request);
    }

    @DeleteMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        policyService.delete(tenant, id);
        return ResponseEntity.noContent().build();
    }

    private static Pageable clampPageable(int page, int size, Sort sort) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        return PageRequest.of(page, size, sort);
    }
}
