package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDisplayPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #508 slice-2b — MockMvc wire-shape regression for
 * {@link AdminEndpointDisplayPolicyController}. Confirms canonical
 * {@link ResponseStatusException} codes pass through and the happy PUT serializes
 * the response shape (incl. the open-proposal projection).
 */
@WebMvcTest(AdminEndpointDisplayPolicyController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointDisplayPolicyControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = "alice@example.com";
    private static final UUID DEVICE_ID = UUID.randomUUID();

    private static final String ENFORCE_BODY = """
            {"operation":"ENFORCE","reason":"kiosk",
             "screensaver":{"enabled":true,"timeoutSeconds":600,"secureOnResume":true,
                            "scrPath":"c:\\\\windows\\\\system32\\\\scrnsave.scr"}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointDisplayPolicyService service;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void enforce_happy_returns200WithOpenProposal() throws Exception {
        ctx();
        AdminDisplayPolicyResponse.OpenProposal proposal = new AdminDisplayPolicyResponse.OpenProposal(
                UUID.randomUUID(), UUID.randomUUID(), "ENFORCE", "a".repeat(64),
                "PENDING", "QUEUED", SUBJECT, null);
        AdminDisplayPolicyResponse res = new AdminDisplayPolicyResponse(
                DEVICE_ID, null, null, null, null, null, null, null, null,
                null, null, null, null, null, proposal);
        when(service.enforce(any(), eq(DEVICE_ID), any())).thenReturn(res);

        mockMvc.perform(put("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ENFORCE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openProposal.operation").value("ENFORCE"))
                .andExpect(jsonPath("$.openProposal.approvalStatus").value("PENDING"));
    }

    @Test
    void enforce_featureFlagOff_returns503() throws Exception {
        ctx();
        when(service.enforce(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "disabled"));
        mockMvc.perform(put("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ENFORCE_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void enforce_validationFailure_returns400() throws Exception {
        ctx();
        when(service.enforce(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad scr"));
        mockMvc.perform(put("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ENFORCE_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enforce_differentProposalPending_returns409() throws Exception {
        ctx();
        when(service.enforce(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "pending"));
        mockMvc.perform(put("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(ENFORCE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void clear_noManagedPolicy_returns404() throws Exception {
        ctx();
        when(service.clear(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing to clear"));
        mockMvc.perform(delete("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"undo\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_noPolicy_returns404() throws Exception {
        ctx();
        when(service.get(any(), eq(DEVICE_ID)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no policy"));
        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/display-policy", DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    private void ctx() {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
    }
}
