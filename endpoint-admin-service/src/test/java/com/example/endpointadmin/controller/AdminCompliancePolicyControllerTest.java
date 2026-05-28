package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.CompliancePolicyItemResponse;
import com.example.endpointadmin.model.ComplianceEnforcementMode;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.EndpointCompliancePolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCompliancePolicyController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminCompliancePolicyControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID POLICY_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CATALOG_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EndpointCompliancePolicyService policyService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void listReturnsPaginatedShape() throws Exception {
        bindTenantContext();
        Page<CompliancePolicyItemResponse> page = new PageImpl<>(
                List.of(buildResponse()), PageRequest.of(0, 25), 1);
        when(policyService.list(any(AdminTenantContext.class), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/compliance/policy-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void createReturns201() throws Exception {
        bindTenantContext();
        when(policyService.create(any(AdminTenantContext.class), any()))
                .thenReturn(buildResponse());

        mockMvc.perform(post("/api/v1/admin/compliance/policy-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "catalogItemId", CATALOG_ID.toString(),
                                "enforcementMode", "REQUIRED",
                                "enabled", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(POLICY_ID.toString()))
                .andExpect(jsonPath("$.enforcementMode").value("REQUIRED"));
    }

    @Test
    void createReturns400WhenBodyMissingRequiredFields() throws Exception {
        bindTenantContext();

        mockMvc.perform(post("/api/v1/admin/compliance/policy-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns400ForCrossTenantCatalog() throws Exception {
        bindTenantContext();
        when(policyService.create(any(AdminTenantContext.class), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Catalog item does not exist in the current tenant."));

        mockMvc.perform(post("/api/v1/admin/compliance/policy-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "catalogItemId", CATALOG_ID.toString(),
                                "enforcementMode", "REQUIRED"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturns200() throws Exception {
        bindTenantContext();
        when(policyService.update(any(AdminTenantContext.class), eq(POLICY_ID), any()))
                .thenReturn(buildResponse(ComplianceEnforcementMode.FORBIDDEN));

        mockMvc.perform(put("/api/v1/admin/compliance/policy-items/{id}", POLICY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "catalogItemId", CATALOG_ID.toString(),
                                "enforcementMode", "FORBIDDEN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforcementMode").value("FORBIDDEN"));
    }

    @Test
    void deleteReturns204() throws Exception {
        bindTenantContext();
        mockMvc.perform(delete("/api/v1/admin/compliance/policy-items/{id}", POLICY_ID))
                .andExpect(status().isNoContent());
    }

    private void bindTenantContext() {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com"));
    }

    private CompliancePolicyItemResponse buildResponse() {
        return buildResponse(ComplianceEnforcementMode.REQUIRED);
    }

    private CompliancePolicyItemResponse buildResponse(ComplianceEnforcementMode mode) {
        return new CompliancePolicyItemResponse(
                POLICY_ID,
                TENANT_ID,
                CATALOG_ID,
                "7zip.7zip",
                "7-Zip",
                mode,
                true,
                "creator",
                Instant.parse("2026-05-28T10:00:00Z"),
                "creator",
                Instant.parse("2026-05-28T10:00:00Z"),
                0L);
    }
}
