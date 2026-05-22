package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAdminCommandService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointCommandController {

    private final EndpointAdminCommandService commandService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointCommandController(EndpointAdminCommandService commandService,
                                          TenantContextResolver tenantContextResolver) {
        this.commandService = commandService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-devices/{deviceId}/commands")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto createCommand(@PathVariable UUID deviceId,
                                            @Valid @RequestBody CreateEndpointCommandRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.createCommand(context, deviceId, request);
    }

    @PostMapping("/endpoint-commands")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto createCommand(@Valid @RequestBody CreateEndpointCommandRequest request) {
        if (request.deviceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint device id is required.");
        }
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.createCommand(context, request.deviceId(), request);
    }

    /**
     * BE-017 — a second admin records the dual-control decision on a
     * destructive command pending approval. Manager-relation gated; the
     * service enforces that the approver is not the issuer.
     */
    @PostMapping("/endpoint-commands/{commandId}/approval")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto approveCommand(@PathVariable UUID commandId,
                                             @Valid @RequestBody ApproveEndpointCommandRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.approveCommand(context, commandId, request);
    }

    @GetMapping("/endpoint-commands")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<EndpointCommandDto> listCommands(@RequestParam(required = false) UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.listCommands(context, deviceId);
    }

    @GetMapping("/endpoint-devices/{deviceId}/commands")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<EndpointCommandDto> listDeviceCommands(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.listDeviceCommands(context, deviceId);
    }

    @GetMapping("/endpoint-commands/{commandId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public EndpointCommandDto getCommand(@PathVariable UUID commandId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.getCommand(context, commandId);
    }
}
