package com.example.endpointadmin.controller;

import com.example.endpointadmin.dto.v1.EndpointAgentServiceStatusDto;
import com.example.endpointadmin.service.EndpointAgentStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/endpoint-agents")
public class EndpointAgentStatusController {

    private final EndpointAgentStatusService statusService;

    public EndpointAgentStatusController(EndpointAgentStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public EndpointAgentServiceStatusDto getStatus() {
        return statusService.currentStatus();
    }
}
