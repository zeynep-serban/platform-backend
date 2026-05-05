package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/endpoint-devices")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminEndpointDeviceController {

    private final EndpointDeviceService deviceService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDeviceController(EndpointDeviceService deviceService,
                                         TenantContextResolver tenantContextResolver) {
        this.deviceService = deviceService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    public List<EndpointDeviceDto> listDevices() {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return deviceService.listDevices(context.tenantId());
    }

    @GetMapping("/{deviceId}")
    public EndpointDeviceDto getDevice(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return deviceService.getDevice(context.tenantId(), deviceId);
    }
}
