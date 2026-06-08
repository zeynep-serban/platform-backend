package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.endpointadmin.config.EndpointAdminWebMvcConfig;
import com.example.endpointadmin.config.SecurityConfig;
import com.example.endpointadmin.dto.compliancegap.ComplianceGapResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.DeviceCredentialProvider;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointComplianceGapService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.7 COMPLETED-promotion gate (Codex {@code 019ea95d} finding 6): the
 * compliance-gap controller was NOT covered by
 * {@link AdminEndpointAuthorizationSecurityTest} (which only loads the device +
 * maintenance-token controllers). This dedicated class proves the Secured tier
 * for the read-only mart:
 * <ul>
 *   <li>a caller WITHOUT endpoint-admin {@code can_view} gets {@code 403} and
 *       the service is never invoked (fail-closed), and</li>
 *   <li>a viewer WITH {@code can_view} gets {@code 200}.</li>
 * </ul>
 *
 * <p>Mirrors the JWT/scope + OpenFGA mock shape of the sibling authorization
 * security test so the {@code @RequireModule} interceptor path is exercised.
 */
@WebMvcTest(controllers = AdminEndpointComplianceGapController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, EndpointAdminWebMvcConfig.class})
class AdminEndpointComplianceGapSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointComplianceGapService gapService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    // Required by SecurityConfig#agentSecurityFilterChain bean wiring even though
    // the compliance-gap controller is on the admin chain.
    @MockitoBean
    private DeviceCredentialProvider deviceCredentialProvider;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com"));
    }

    @Test
    void deniedViewerCannotReadComplianceGap() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.VIEWER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap").with(adminJwt("user-1")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(gapService);
    }

    @Test
    void viewerCanReadComplianceGap() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.VIEWER, "module", EndpointAdminAuthz.MODULE))
                .thenReturn(true);
        when(gapService.findGaps(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ComplianceGapResponse(
                        List.of(), 0, 1, 50,
                        Map.of("gapTypes", List.of("pending_security_updates", "rdp_enabled"),
                                "freshnessWindow", "PT168H", "page", 1, "pageSize", 50),
                        Instant.parse("2026-06-09T00:00:00Z")));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap").with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.filterEcho.freshnessWindow").value("PT168H"));
    }

    /**
     * Attaches {@code SCOPE_endpoint-admin} so the admin security filter chain
     * admits the request and the {@code @RequireModule} / OpenFGA interceptor
     * path is the gate under test (mirrors the sibling security test helper).
     */
    private static RequestPostProcessor adminJwt(String subject) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_endpoint-admin"));
    }
}
