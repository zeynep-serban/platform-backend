package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointMaintenanceTokenDto;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.MaintenanceTokenStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointMaintenanceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminMaintenanceTokenController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminMaintenanceTokenControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TOKEN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointMaintenanceTokenService maintenanceTokenService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void createTokenReturnsPlainTokenOnce() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(maintenanceTokenService.createToken(eq(context), eq(DEVICE_ID), any()))
                .thenReturn(new CreateMaintenanceTokenResponse(
                        TOKEN_ID,
                        "plain-maintenance-token",
                        MaintenanceAction.UNINSTALL_AGENT,
                        Instant.parse("2026-04-28T11:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/maintenance-tokens", DEVICE_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "action": "UNINSTALL_AGENT",
                                  "reason": "offboarding",
                                  "expiresInMinutes": 60
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenId").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$.token").value("plain-maintenance-token"))
                .andExpect(jsonPath("$.action").value("UNINSTALL_AGENT"))
                .andExpect(jsonPath("$.expiresAt").value("2026-04-28T11:00:00Z"));

        verify(maintenanceTokenService).createToken(eq(context), eq(DEVICE_ID), any());
    }

    @Test
    void createTokenRejectsMissingReasonAndExpiry() throws Exception {
        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/maintenance-tokens", DEVICE_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "action": "UNINSTALL_AGENT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(maintenanceTokenService);
    }

    @Test
    void listTokensReturnsDeviceTokens() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(maintenanceTokenService.listTokens(context, DEVICE_ID)).thenReturn(List.of(tokenDto(MaintenanceTokenStatus.PENDING)));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/maintenance-tokens", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$[0].deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$[0].action").value("STOP_AGENT"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void getTokenReturnsTokenStatus() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(maintenanceTokenService.getToken(context, TOKEN_ID)).thenReturn(tokenDto(MaintenanceTokenStatus.PENDING));

        mockMvc.perform(get("/api/v1/admin/maintenance-tokens/{tokenId}", TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void revokeTokenReturnsRevokedTokenStatus() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(maintenanceTokenService.revokeToken(context, TOKEN_ID)).thenReturn(tokenDto(MaintenanceTokenStatus.REVOKED));

        mockMvc.perform(delete("/api/v1/admin/maintenance-tokens/{tokenId}", TOKEN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointMaintenanceTokenDto tokenDto(MaintenanceTokenStatus status) {
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        return new EndpointMaintenanceTokenDto(
                TOKEN_ID,
                TENANT_ID,
                DEVICE_ID,
                MaintenanceAction.STOP_AGENT,
                status,
                "maintenance",
                "admin@example.com",
                Instant.parse("2026-04-28T11:00:00Z"),
                null,
                null,
                now,
                now
        );
    }
}
