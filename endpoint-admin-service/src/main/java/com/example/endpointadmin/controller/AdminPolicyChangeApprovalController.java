package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.ApproveRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.AttestRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.DelegateRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RejectRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RequestChangesRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.ProposePolicyChangeRequest;
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.PolicyChangeApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Wave-12 PR-5 — admin REST surface for the policy-change approval
 * workflow. Exposed via the gateway at
 * {@code /api/v1/endpoint-admin/policy-approvals/**} which the gateway
 * RewritePath maps to {@code /api/v1/admin/policy-approvals/**} on this
 * service (same shape as the other endpoint-admin admin routes).
 *
 * <p>All endpoints are gated by the {@code endpoint-admin} module: read
 * paths require {@code can_view}, mutation paths require {@code
 * can_manage}. The 4-eyes guard (proposer cannot approve own request)
 * is enforced in the service and surfaces as {@code 403 proposer_self}.
 */
@RestController
@RequestMapping("/api/v1/admin/policy-approvals")
public class AdminPolicyChangeApprovalController {

    private final PolicyChangeApprovalService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminPolicyChangeApprovalController(PolicyChangeApprovalService service,
                                                TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public List<PolicyChangeApprovalDto> list(
            @RequestParam(name = "status", required = false) PolicyApprovalStatus status,
            @RequestParam(name = "policyId", required = false) String policyId,
            @RequestParam(name = "proposer", required = false) String proposerSubject) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.list(context, status, policyId, proposerSubject);
    }

    @GetMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public PolicyChangeApprovalDto get(@PathVariable UUID id) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.get(context, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto propose(
            @Valid @RequestBody ProposePolicyChangeRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.propose(context, request);
    }

    @PostMapping("/{id}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto approve(@PathVariable UUID id,
                                           @Valid @RequestBody ApproveRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.approve(context, id, request);
    }

    @PostMapping("/{id}/reject")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto reject(@PathVariable UUID id,
                                          @Valid @RequestBody RejectRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.reject(context, id, request);
    }

    @PostMapping("/{id}/request-changes")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto requestChanges(
            @PathVariable UUID id,
            @Valid @RequestBody RequestChangesRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.requestChanges(context, id, request);
    }

    @PostMapping("/{id}/delegate")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto delegate(@PathVariable UUID id,
                                            @Valid @RequestBody DelegateRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.delegate(context, id, request);
    }

    @PostMapping("/{id}/attest")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto attest(@PathVariable UUID id,
                                          @Valid @RequestBody AttestRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.attest(context, id, request);
    }
}
