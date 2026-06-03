package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointUninstallMakerCheckerViolationException;
import com.example.endpointadmin.service.EndpointUninstallService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AG-028 Phase 1b — MockMvc wire-shape regression for
 * {@link AdminEndpointUninstallController}.
 *
 * <p>Mirrors {@code AdminCatalogUninstallSettingsControllerTest} on the
 * destructive side. Confirms that the service's custom exception
 * ({@link EndpointUninstallMakerCheckerViolationException}) surfaces as
 * HTTP 403 (not HTTP 500 via the catch-all
 * {@code GlobalExceptionHandler}). Confirms the canonical
 * {@link ResponseStatusException} status codes pass through correctly for
 * the feature-flag and retryable rejects.
 */
@WebMvcTest(AdminEndpointUninstallController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointUninstallControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = "alice@example.com";
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointUninstallService service;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void approveMakerCheckerViolation_returns403_notInternalError() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.approve(any(), eq(DEVICE_ID), eq(REQUEST_ID), any()))
                .thenThrow(new EndpointUninstallMakerCheckerViolationException(
                        REQUEST_ID, "alice@example.com", "alice@example.com"));

        mockMvc.perform(post(
                        "/api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{rid}/approve",
                        DEVICE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void propose_featureFlagOff_returns503() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.propose(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "AG-028 managed uninstall surface is disabled"));

        mockMvc.perform(post(
                        "/api/v1/admin/endpoint-devices/{deviceId}/uninstalls", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":\"7zip\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void approve_staleHeartbeat_returns424() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.approve(any(), eq(DEVICE_ID), eq(REQUEST_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                        "Agent heartbeat is stale"));

        mockMvc.perform(post(
                        "/api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{rid}/approve",
                        DEVICE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isFailedDependency());
    }

    @Test
    void approve_capabilityMissing_returns422() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.approve(any(), eq(DEVICE_ID), eq(REQUEST_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Agent does not advertise the 'UNINSTALL_SOFTWARE' capability"));

        mockMvc.perform(post(
                        "/api/v1/admin/endpoint-devices/{deviceId}/uninstalls/{rid}/approve",
                        DEVICE_ID, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void propose_inFlight_returns409() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.propose(any(), eq(DEVICE_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "An in-flight uninstall already exists"));

        mockMvc.perform(post(
                        "/api/v1/admin/endpoint-devices/{deviceId}/uninstalls", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogItemId\":\"7zip\"}"))
                .andExpect(status().isConflict());
    }
}
