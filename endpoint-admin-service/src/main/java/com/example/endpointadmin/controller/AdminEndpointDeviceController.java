package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.DeviceLifecycleRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.dto.v1.admin.UpdateDeviceRolloutRequest;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDeviceLifecycleService;
import com.example.endpointadmin.service.EndpointDeviceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/endpoint-devices")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminEndpointDeviceController {

    private final EndpointDeviceService deviceService;
    private final EndpointDeviceLifecycleService lifecycleService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDeviceController(EndpointDeviceService deviceService,
                                         EndpointDeviceLifecycleService lifecycleService,
                                         TenantContextResolver tenantContextResolver) {
        this.deviceService = deviceService;
        this.lifecycleService = lifecycleService;
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

    @PatchMapping("/{deviceId}/rollout")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointDeviceDto updateRolloutAssignment(
            @PathVariable UUID deviceId,
            @Valid @RequestBody UpdateDeviceRolloutRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return deviceService.updateRolloutAssignment(context.tenantId(), deviceId, request);
    }

    /**
     * Decommission a device (KVKK: deactivate, not delete — reversible). 409 if
     * already decommissioned. The cascade + the "cannot act or revive itself"
     * invariant guards land in the same PR.
     */
    @PostMapping("/{deviceId}/decommission")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointDeviceDto decommission(
            @PathVariable UUID deviceId,
            @Valid @RequestBody DeviceLifecycleRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return lifecycleService.decommission(
                context.tenantId(), context.subject(), deviceId, request.reason());
    }

    /**
     * Reverse a decommission. 409 if the device is not decommissioned. Target
     * state is OFFLINE for enrolled devices (ONLINE is earned by a real
     * heartbeat) — see EndpointDeviceLifecycleService.
     */
    @PostMapping("/{deviceId}/reactivate")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointDeviceDto reactivate(
            @PathVariable UUID deviceId,
            @Valid @RequestBody DeviceLifecycleRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return lifecycleService.reactivate(
                context.tenantId(), context.subject(), deviceId, request.reason());
    }
}
