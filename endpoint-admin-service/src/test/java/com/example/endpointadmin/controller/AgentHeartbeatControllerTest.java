package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.security.DeviceCredentialAuthenticationToken;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointHeartbeatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentHeartbeatController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AgentHeartbeatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointHeartbeatService heartbeatService;

    @Test
    void heartbeatReturnsAcceptedResponse() throws Exception {
        UUID deviceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        DeviceCredentialResult principal = new DeviceCredentialResult(
                deviceId.toString(),
                "credential-1",
                Instant.parse("2026-04-28T10:00:00Z")
        );
        when(heartbeatService.recordHeartbeat(any(), any(), eq("127.0.0.1")))
                .thenReturn(new AgentHeartbeatResponse(
                        true,
                        deviceId,
                        DeviceStatus.ONLINE,
                        Instant.parse("2026-04-28T10:01:00Z")
                ));

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .with(authentication(DeviceCredentialAuthenticationToken.authenticated(principal)))
                        .contentType("application/json")
                        .content("""
                                {
                                  "installId": "install-1",
                                  "hostname": "PC-001",
                                  "osFamily": "WINDOWS",
                                  "architecture": "amd64",
                                  "agentVersion": "0.2.0",
                                  "osVersion": "Windows 11 Pro",
                                  "state": "ONLINE",
                                  "capabilities": ["COLLECT_INVENTORY", "LIST_LOCAL_USERS"],
                                  "timestamp": "2026-04-28T10:00:00Z",
                                  "inventory": {"cpuCount": 8},
                                  "localUsers": [{"username": "local.user", "enabled": true}],
                                  "metrics": {"queueDepth": 0}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.serverTime").value("2026-04-28T10:01:00Z"));

        verify(heartbeatService).recordHeartbeat(eq(principal), any(), eq("127.0.0.1"));
    }

    @Test
    void heartbeatValidatesRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "state": "ONLINE"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
