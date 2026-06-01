package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminServicesSnapshotResponse;
import com.example.endpointadmin.model.EndpointServicesSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointServicesService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BE — critical services admin REST surface (Faz 22.5, AG-039-be).
 * Mirrors AG-038-be {@code AdminEndpointDiagnosticsController}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointServicesController {

    private final EndpointServicesService servicesService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointServicesController(
            EndpointServicesService servicesService,
            TenantContextResolver tenantContextResolver) {
        this.servicesService = servicesService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/services/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminServicesSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointServicesSnapshot snapshot = servicesService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No services snapshot for device " + deviceId));
        return AdminServicesSnapshotResponse.from(snapshot);
    }
}
