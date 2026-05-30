package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.ComplianceEvaluationListResponse;
import com.example.endpointadmin.dto.v1.admin.DeviceProhibitedSoftwareResponse;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.ProhibitedSoftwareFindingService;
import com.example.endpointadmin.service.compliance.ProhibitedSoftwareRuleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
 * BE-025 — prohibited-software detection admin REST surface (Faz 22.5).
 *
 * <pre>
 * Denylist rule CRUD (tenant-scoped, NOT catalog-bound):
 *   GET    /api/v1/admin/prohibited-software/rules?page=&amp;size=  — list  (can_view)
 *   GET    /api/v1/admin/prohibited-software/rules/{id}           — single(can_view)
 *   POST   /api/v1/admin/prohibited-software/rules                — create(can_manage)
 *   PUT    /api/v1/admin/prohibited-software/rules/{id}           — update(can_manage)
 *   DELETE /api/v1/admin/prohibited-software/rules/{id}           — delete(can_manage)
 *
 * Device-facing findings (read from the last persisted evaluation evidence):
 *   GET    /api/v1/admin/endpoint-devices/{deviceId}/prohibited-software
 *          — 200 always (NO_EVALUATION when none / unknown / cross-tenant);
 *            no existence leak. (can_view)
 * </pre>
 *
 * <p>Reuses the {@code module:endpoint-admin} OpenFGA relations
 * ({@code can_view} read / {@code can_manage} mutate) — no new scope opened,
 * exactly like BE-020I / BE-023 / BE-024. Detection + surface only: there is
 * deliberately no uninstall / remediation route.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProhibitedSoftwareController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProhibitedSoftwareRuleService ruleService;
    private final ProhibitedSoftwareFindingService findingService;
    private final TenantContextResolver tenantContextResolver;

    public AdminProhibitedSoftwareController(
            ProhibitedSoftwareRuleService ruleService,
            ProhibitedSoftwareFindingService findingService,
            TenantContextResolver tenantContextResolver) {
        this.ruleService = ruleService;
        this.findingService = findingService;
        this.tenantContextResolver = tenantContextResolver;
    }

    // ──────────────────────────────────────────────────────────────────
    // Denylist rule CRUD
    // ──────────────────────────────────────────────────────────────────

    @GetMapping("/prohibited-software/rules")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ComplianceEvaluationListResponse<ProhibitedSoftwareRuleResponse> list(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        Pageable pageable = clampPageable(page, size,
                Sort.by(Sort.Direction.DESC, "lastUpdatedAt"));
        Page<ProhibitedSoftwareRuleResponse> result = ruleService.list(tenant, pageable);
        return new ComplianceEvaluationListResponse<>(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/prohibited-software/rules/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ProhibitedSoftwareRuleResponse get(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return ruleService.get(tenant, id);
    }

    @PostMapping("/prohibited-software/rules")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<ProhibitedSoftwareRuleResponse> create(
            @Valid @RequestBody ProhibitedSoftwareRuleRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        ProhibitedSoftwareRuleResponse created = ruleService.create(tenant, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/prohibited-software/rules/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ProhibitedSoftwareRuleResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ProhibitedSoftwareRuleRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return ruleService.update(tenant, id, request);
    }

    @DeleteMapping("/prohibited-software/rules/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        ruleService.delete(tenant, id);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────────────────────────────────────────────────────
    // Device-facing findings (read persisted evidence)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Prohibited-software findings for a device, read from the last
     * persisted compliance evaluation evidence (NOT a live recompute — see
     * {@link ProhibitedSoftwareFindingService}). Always HTTP 200 with a
     * {@code status} field; an unknown / cross-tenant device is
     * indistinguishable from "no evaluation yet" (no existence leak).
     * {@code @Transactional(readOnly=true)} keeps the session open while the
     * service walks the stored JSONB evidence
     * ({@code spring.jpa.open-in-view=false}).
     */
    @GetMapping("/endpoint-devices/{deviceId}/prohibited-software")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public DeviceProhibitedSoftwareResponse getDeviceFindings(@PathVariable UUID deviceId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return findingService.getDeviceFindings(tenant, deviceId);
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
