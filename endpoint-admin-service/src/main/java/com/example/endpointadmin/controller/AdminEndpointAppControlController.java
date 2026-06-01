package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminAppControlSnapshotResponse;
import com.example.endpointadmin.model.EndpointAppControlSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAppControlService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BE — Application Control admin REST surface (Faz 22.5, AG-041-be).
 * Mirrors AG-040-be {@code AdminEndpointStartupExposureController}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointAppControlController {

    private final EndpointAppControlService appControlService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointAppControlController(
            EndpointAppControlService appControlService,
            TenantContextResolver tenantContextResolver) {
        this.appControlService = appControlService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/app-control/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminAppControlSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointAppControlSnapshot snapshot = appControlService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No app-control snapshot for device " + deviceId));
        return AdminAppControlSnapshotResponse.from(snapshot);
    }
}
