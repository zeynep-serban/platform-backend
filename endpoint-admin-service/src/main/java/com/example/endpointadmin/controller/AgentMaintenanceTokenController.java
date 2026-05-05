package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenResponse;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointMaintenanceTokenService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/maintenance-tokens")
public class AgentMaintenanceTokenController {

    private final EndpointMaintenanceTokenService maintenanceTokenService;

    public AgentMaintenanceTokenController(EndpointMaintenanceTokenService maintenanceTokenService) {
        this.maintenanceTokenService = maintenanceTokenService;
    }

    @PostMapping("/consume")
    public ResponseEntity<ConsumeMaintenanceTokenResponse> consumeToken(
            @AuthenticationPrincipal DeviceCredentialResult principal,
            @Valid @RequestBody ConsumeMaintenanceTokenRequest request) {
        return ResponseEntity.accepted().body(maintenanceTokenService.consumeToken(principal, request));
    }
}
