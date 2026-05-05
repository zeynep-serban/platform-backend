package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenResponse;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.security.DeviceCredentialAuthenticationToken;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointMaintenanceTokenService;
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

@WebMvcTest(AgentMaintenanceTokenController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AgentMaintenanceTokenControllerTest {

    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointMaintenanceTokenService maintenanceTokenService;

    @Test
    void consumeTokenReturnsAuthorizedMaintenanceAction() throws Exception {
        DeviceCredentialResult principal = principal();
        when(maintenanceTokenService.consumeToken(eq(principal), any()))
                .thenReturn(new ConsumeMaintenanceTokenResponse(
                        MaintenanceAction.UNINSTALL_AGENT,
                        DEVICE_ID,
                        Instant.parse("2026-04-28T10:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/agent/maintenance-tokens/consume")
                        .with(authentication(DeviceCredentialAuthenticationToken.authenticated(principal)))
                        .contentType("application/json")
                        .content("""
                                {
                                  "maintenanceToken": "plain-maintenance-token",
                                  "agentVersion": "0.3.0"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.action").value("UNINSTALL_AGENT"))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.consumedAt").value("2026-04-28T10:00:00Z"));

        verify(maintenanceTokenService).consumeToken(eq(principal), any());
    }

    private DeviceCredentialResult principal() {
        return new DeviceCredentialResult(
                DEVICE_ID.toString(),
                "credential-1",
                Instant.parse("2026-04-28T10:00:00Z")
        );
    }
}
