package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.EndpointAgentServiceStatusDto;
import com.example.endpointadmin.service.EndpointAgentStatusService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EndpointAgentStatusController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class EndpointAgentStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAgentStatusService statusService;

    @Test
    void getStatus_returnsServiceStatus() throws Exception {
        when(statusService.currentStatus()).thenReturn(new EndpointAgentServiceStatusDto(
                "endpoint-admin-service",
                "UP",
                "v1",
                "unsupported",
                Instant.parse("2026-04-28T10:00:00Z")
        ));

        mockMvc.perform(get("/api/v1/endpoint-agents/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("endpoint-admin-service"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.deviceCredentialProvider").value("unsupported"))
                .andExpect(jsonPath("$.timestamp").value("2026-04-28T10:00:00Z"));
    }
}
