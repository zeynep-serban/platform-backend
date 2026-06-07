package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateAgentUpdateRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateLocalPasswordChangeRequest;
import com.example.endpointadmin.dto.v1.admin.CreateLocalPasswordChangeResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAdminCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointCommandController {

    private static final Set<String> CREATE_AGENT_UPDATE_ALLOWED_FIELDS = Set.of(
            "releaseId",
            "idempotencyKey",
            "reason",
            "requiredDeploymentRing",
            "notBefore",
            "expiresAt");

    private final EndpointAdminCommandService commandService;
    private final TenantContextResolver tenantContextResolver;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AdminEndpointCommandController(EndpointAdminCommandService commandService,
                                          TenantContextResolver tenantContextResolver,
                                          ObjectMapper objectMapper,
                                          Validator validator) {
        this.commandService = commandService;
        this.tenantContextResolver = tenantContextResolver;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/endpoint-devices/{deviceId}/commands")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto createCommand(@PathVariable UUID deviceId,
                                            @Valid @RequestBody CreateEndpointCommandRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.createCommand(context, deviceId, request);
    }

    /**
     * BE-032 — dedicated UPDATE_AGENT dispatch surface. The service resolves
     * release trust metadata from the approved release catalog; the generic
     * command endpoint intentionally rejects caller-supplied UPDATE_AGENT
     * payloads.
     */
    @PostMapping("/endpoint-devices/{deviceId}/agent-updates")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto createAgentUpdate(@PathVariable UUID deviceId,
                                                @RequestBody Map<String, Object> requestBody) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        CreateAgentUpdateRequest request = parseCreateAgentUpdateRequest(requestBody);
        return commandService.createAgentUpdate(context, deviceId, request);
    }

    /**
     * AG-042 — dedicated local recovery password path. The response returns the
     * generated password once; command list/get DTOs keep only a secretRef and
     * never include raw password material.
     */
    @PostMapping("/endpoint-devices/{deviceId}/local-password-changes")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public CreateLocalPasswordChangeResponse createLocalPasswordChange(
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateLocalPasswordChangeRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.createLocalPasswordChange(context, deviceId, request);
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

    private CreateAgentUpdateRequest parseCreateAgentUpdateRequest(
            Map<String, Object> requestBody) {
        if (requestBody == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Agent update request body is required.");
        }
        Set<String> unsupported = requestBody.keySet().stream()
                .filter(key -> !CREATE_AGENT_UPDATE_ALLOWED_FIELDS.contains(key))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!unsupported.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Agent update request contains unsupported field(s): "
                            + String.join(", ", unsupported));
        }

        CreateAgentUpdateRequest request;
        try {
            request = objectMapper.convertValue(requestBody,
                    CreateAgentUpdateRequest.class);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Agent update request body could not be parsed.", ex);
        }

        Set<ConstraintViolation<CreateAgentUpdateRequest>> violations =
                validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath()
                            + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return request;
    }
}
