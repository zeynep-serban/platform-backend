package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.security.DeviceCredentialAuthenticationToken;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.service.EndpointAgentCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentCommandController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AgentCommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAgentCommandService commandService;

    @Test
    void nextCommandReturnsClaimedCommand() throws Exception {
        DeviceCredentialResult principal = principal();
        UUID commandId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(commandService.claimNext(any())).thenReturn(Optional.of(new AgentCommandResponse(
                commandId,
                "claim-1",
                1,
                CommandType.COLLECT_INVENTORY,
                "admin@example.com",
                "inventory refresh",
                Map.of("reason", "inventory refresh"),
                Instant.parse("2026-04-28T10:05:00Z")
        )));

        mockMvc.perform(get("/api/v1/agent/commands/next")
                        .with(authentication(DeviceCredentialAuthenticationToken.authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.claimId").value("claim-1"))
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.type").value("COLLECT_INVENTORY"))
                .andExpect(jsonPath("$.requestedBy").value("admin@example.com"))
                .andExpect(jsonPath("$.reason").value("inventory refresh"))
                .andExpect(jsonPath("$.claimExpiresAt").value("2026-04-28T10:05:00Z"));

        verify(commandService).claimNext(eq(principal));
    }

    @Test
    void nextCommandReturnsNoContentWhenEmpty() throws Exception {
        DeviceCredentialResult principal = principal();
        when(commandService.claimNext(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/agent/commands/next")
                        .with(authentication(DeviceCredentialAuthenticationToken.authenticated(principal))))
                .andExpect(status().isNoContent());
    }

    @Test
    void submitResultReturnsAccepted() throws Exception {
        DeviceCredentialResult principal = principal();
        UUID commandId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        mockMvc.perform(post("/api/v1/agent/commands/{commandId}/result", commandId)
                        .with(authentication(DeviceCredentialAuthenticationToken.authenticated(principal)))
                        .contentType("application/json")
                        .content("""
                                {
                                  "claimId": "claim-1",
                                  "attemptNumber": 1,
                                  "status": "SUCCEEDED",
                                  "summary": "done",
                                  "details": {"inventory": {"hostname": "PC-001"}},
                                  "exitCode": 0,
                                  "startedAt": "2026-04-28T10:00:00Z",
                                  "finishedAt": "2026-04-28T10:00:02Z"
                                }
                                """))
                .andExpect(status().isAccepted());

        verify(commandService).submitResult(eq(principal), eq(commandId), any());
    }

    private DeviceCredentialResult principal() {
        return new DeviceCredentialResult(
                "22222222-2222-2222-2222-222222222222",
                "credential-1",
                Instant.parse("2026-04-28T10:00:00Z")
        );
    }
}
