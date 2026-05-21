package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenRequest;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointMaintenanceTokenDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointMaintenanceTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminMaintenanceTokenController {

    private final EndpointMaintenanceTokenService maintenanceTokenService;
    private final TenantContextResolver tenantContextResolver;

    public AdminMaintenanceTokenController(EndpointMaintenanceTokenService maintenanceTokenService,
                                           TenantContextResolver tenantContextResolver) {
        this.maintenanceTokenService = maintenanceTokenService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-devices/{deviceId}/maintenance-tokens")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<CreateMaintenanceTokenResponse> createToken(
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateMaintenanceTokenRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(maintenanceTokenService.createToken(context, deviceId, request));
    }

    @GetMapping("/endpoint-devices/{deviceId}/maintenance-tokens")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<EndpointMaintenanceTokenDto> listTokens(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return maintenanceTokenService.listTokens(context, deviceId);
    }

    @GetMapping("/maintenance-tokens/{tokenId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public EndpointMaintenanceTokenDto getToken(@PathVariable UUID tokenId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return maintenanceTokenService.getToken(context, tokenId);
    }

    @DeleteMapping("/maintenance-tokens/{tokenId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointMaintenanceTokenDto revokeToken(@PathVariable UUID tokenId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return maintenanceTokenService.revokeToken(context, tokenId);
    }
}
