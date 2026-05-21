package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointAgentCommandService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/commands")
public class AgentCommandController {

    private final EndpointAgentCommandService commandService;

    public AgentCommandController(EndpointAgentCommandService commandService) {
        this.commandService = commandService;
    }

    @GetMapping("/next")
    public ResponseEntity<AgentCommandResponse> nextCommand(@AuthenticationPrincipal DeviceCredentialResult principal) {
        return commandService.claimNext(principal)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{commandId}/result")
    public ResponseEntity<Void> submitResult(@AuthenticationPrincipal DeviceCredentialResult principal,
                                             @PathVariable UUID commandId,
                                             @Valid @RequestBody AgentCommandResultRequest request) {
        commandService.submitResult(principal, commandId, request);
        return ResponseEntity.accepted().build();
    }
}
