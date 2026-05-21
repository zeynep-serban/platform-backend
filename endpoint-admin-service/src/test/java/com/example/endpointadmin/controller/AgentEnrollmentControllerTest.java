package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentResponse;
import com.example.endpointadmin.service.EndpointEnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentEnrollmentController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AgentEnrollmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointEnrollmentService enrollmentService;

    @Test
    void consumeEnrollmentReturnsNoStoreCredentialResponse() throws Exception {
        UUID deviceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(enrollmentService.consumeEnrollment(any(), eq("127.0.0.1")))
                .thenReturn(new ConsumeEnrollmentResponse(
                        deviceId,
                        "edc_test",
                        "plain-secret",
                        "HMAC-SHA256",
                        Instant.parse("2026-04-28T10:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/agent/enrollments/consume")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enrollmentToken": "token",
                                  "hostname": "PC-001",
                                  "osType": "WINDOWS",
                                  "osVersion": "Windows 11 Pro",
                                  "agentVersion": "0.1.0",
                                  "machineFingerprint": "fp-001",
                                  "domainName": "corp.local"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.credentialKeyId").value("edc_test"))
                .andExpect(jsonPath("$.secret").value("plain-secret"))
                .andExpect(jsonPath("$.hmacAlgorithm").value("HMAC-SHA256"));
    }
}
