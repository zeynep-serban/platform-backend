package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatRequest;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointHeartbeatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentHeartbeatController {

    private final EndpointHeartbeatService heartbeatService;

    public AgentHeartbeatController(EndpointHeartbeatService heartbeatService) {
        this.heartbeatService = heartbeatService;
    }

    @PostMapping("/heartbeat")
    public AgentHeartbeatResponse heartbeat(@AuthenticationPrincipal DeviceCredentialResult principal,
                                            @Valid @RequestBody AgentHeartbeatRequest request,
                                            HttpServletRequest servletRequest) {
        return heartbeatService.recordHeartbeat(principal, request, resolveRemoteAddress(servletRequest));
    }

    private String resolveRemoteAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
