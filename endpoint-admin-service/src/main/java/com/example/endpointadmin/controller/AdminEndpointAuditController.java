package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.EndpointAuditEventDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/endpoint-audit-events")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminEndpointAuditController {

    private final EndpointAuditService auditService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointAuditController(EndpointAuditService auditService,
                                        TenantContextResolver tenantContextResolver) {
        this.auditService = auditService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    public List<EndpointAuditEventDto> listAuditEvents(@RequestParam(required = false) UUID deviceId,
                                                       @RequestParam(required = false) UUID commandId,
                                                       @RequestParam(required = false) String eventType,
                                                       @RequestParam(defaultValue = "50") int limit) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return auditService.listEvents(context.tenantId(), deviceId, commandId, eventType, limit);
    }
}
