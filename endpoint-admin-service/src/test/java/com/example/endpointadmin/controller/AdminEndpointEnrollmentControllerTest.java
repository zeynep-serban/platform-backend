package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminEndpointEnrollmentController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointEnrollmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointEnrollmentService enrollmentService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void createEnrollmentReturnsTokenWithNoStoreHeaders() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "admin@example.com"));
        when(enrollmentService.createEnrollment(any(), any()))
                .thenReturn(new CreateEndpointEnrollmentResponse(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "plain-token",
                        Instant.parse("2026-04-29T10:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/admin/endpoint-enrollments")
                        .contentType("application/json")
                        .content("{\"expiresInMinutes\":60,\"note\":\"rollout\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.token").value("plain-token"))
                .andExpect(jsonPath("$.enrollmentId").value("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    void listEnrollmentsReturnsCurrentTenantItems() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "admin@example.com"));
        when(enrollmentService.listEnrollments(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/endpoint-enrollments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
