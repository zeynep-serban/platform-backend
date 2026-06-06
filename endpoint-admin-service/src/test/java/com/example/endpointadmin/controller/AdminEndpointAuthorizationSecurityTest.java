package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.endpointadmin.config.EndpointAdminWebMvcConfig;
import com.example.endpointadmin.config.SecurityConfig;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.DeviceCredentialProvider;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDeviceService;
import com.example.endpointadmin.service.EndpointMaintenanceTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AdminEndpointDeviceController.class,
        AdminMaintenanceTokenController.class
})
@ActiveProfiles("test")
@Import({SecurityConfig.class, EndpointAdminWebMvcConfig.class})
class AdminEndpointAuthorizationSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TOKEN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointDeviceService deviceService;

    @MockitoBean
    private EndpointMaintenanceTokenService maintenanceTokenService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private DeviceCredentialProvider deviceCredentialProvider;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired()).thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com"));
    }

    @Test
    void viewerCanReadEndpointDevices() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.VIEWER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(true);
        when(deviceService.listDevices(TENANT_ID)).thenReturn(List.of(deviceDto()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices").with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$[0].hostname").value("PC-001"))
                .andExpect(jsonPath("$[0].deploymentRing").value("PILOT"))
                .andExpect(jsonPath("$[0].deviceTags[0]").value("pilot"));
    }

    @Test
    void deniedViewerCannotReadEndpointDevices() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.VIEWER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/endpoint-devices").with(adminJwt("user-1")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceService);
    }

    @Test
    void viewerCannotCreateMaintenanceToken() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/maintenance-tokens", DEVICE_ID)
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "action": "STOP_AGENT",
                                  "reason": "support maintenance",
                                  "expiresInMinutes": 60
                                }
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(maintenanceTokenService);
    }

    @Test
    void managerCanCreateMaintenanceToken() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(true);
        when(maintenanceTokenService.createToken(any(), eq(DEVICE_ID), any()))
                .thenReturn(new CreateMaintenanceTokenResponse(
                        TOKEN_ID,
                        "plain-maintenance-token",
                        MaintenanceAction.STOP_AGENT,
                        Instant.parse("2026-04-28T11:00:00Z")
                ));

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/maintenance-tokens", DEVICE_ID)
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "action": "STOP_AGENT",
                                  "reason": "support maintenance",
                                  "expiresInMinutes": 60
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenId").value(TOKEN_ID.toString()))
                .andExpect(jsonPath("$.action").value("STOP_AGENT"));
    }

    @Test
    void viewerCannotUpdateDeviceRolloutAssignment() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(patch("/api/v1/admin/endpoint-devices/{deviceId}/rollout", DEVICE_ID)
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "deploymentRing": "IT",
                                  "deviceTags": ["it"]
                                }
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deviceService);
    }

    @Test
    void managerCanUpdateDeviceRolloutAssignment() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(true);
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        EndpointDeviceDto updated = new EndpointDeviceDto(
                DEVICE_ID,
                TENANT_ID,
                "PC-001",
                "Pilot PC",
                OsType.WINDOWS,
                "Windows 11",
                "0.3.0",
                "fp-001",
                "corp.local",
                DeploymentRing.IT,
                Set.of("it", "pilot"),
                DeviceStatus.ONLINE,
                now,
                now,
                now,
                now
        );
        when(deviceService.updateRolloutAssignment(eq(TENANT_ID), eq(DEVICE_ID), any())).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/admin/endpoint-devices/{deviceId}/rollout", DEVICE_ID)
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "deploymentRing": "IT",
                                  "deviceTags": ["it", "pilot"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentRing").value("IT"))
                .andExpect(jsonPath("$.deviceTags").isArray());
    }

    @Test
    void disabledOpenFgaAllowsAuthenticatedAdminRequest() throws Exception {
        when(authzService.isEnabled()).thenReturn(false);
        when(deviceService.listDevices(TENANT_ID)).thenReturn(List.of(deviceDto()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices").with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hostname").value("PC-001"));
    }

    /**
     * Admin filter chain (SecurityConfig.adminSecurityFilterChain) requires the
     * JWT to carry one of: ROLE_ADMIN, ROLE_ENDPOINT_ADMIN, SCOPE_endpoint-admin.
     * A subject-only JWT is rejected at the Spring Security layer with 403,
     * never reaching the @RequireModule interceptor — so OpenFGA mocks alone
     * cannot make the request green. This helper attaches SCOPE_endpoint-admin
     * (the realistic shape that `JwtAuthenticationConverter` produces from the
     * `scope` claim) so tests exercise the OpenFGA / interceptor path that
     * they are actually trying to validate.
     */
    private static RequestPostProcessor adminJwt(String subject) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_endpoint-admin"));
    }

    private EndpointDeviceDto deviceDto() {
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        return new EndpointDeviceDto(
                DEVICE_ID,
                TENANT_ID,
                "PC-001",
                "Pilot PC",
                OsType.WINDOWS,
                "Windows 11",
                "0.3.0",
                "fp-001",
                "corp.local",
                DeploymentRing.PILOT,
                Set.of("pilot"),
                DeviceStatus.ONLINE,
                now,
                now,
                now,
                now
        );
    }
}
