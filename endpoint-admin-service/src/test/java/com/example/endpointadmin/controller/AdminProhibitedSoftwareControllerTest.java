package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.endpointadmin.config.EndpointAdminWebMvcConfig;
import com.example.endpointadmin.config.SecurityConfig;
import com.example.endpointadmin.dto.v1.admin.DeviceProhibitedSoftwareResponse;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareFindingResponse;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleResponse;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.DeviceCredentialProvider;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.ProhibitedSoftwareFindingService;
import com.example.endpointadmin.service.compliance.ProhibitedSoftwareRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-025 — MockMvc + RBAC tests for {@link AdminProhibitedSoftwareController}
 * (Faz 22.5). Uses the real {@link SecurityConfig} + a mocked
 * {@link OpenFgaAuthzService} so the {@code @RequireModule} can_view /
 * can_manage enforcement is genuinely exercised (403 on deny), mirroring
 * {@code AdminEndpointAuthorizationSecurityTest}.
 */
@WebMvcTest(AdminProhibitedSoftwareController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, EndpointAdminWebMvcConfig.class})
class AdminProhibitedSoftwareControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RULE_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProhibitedSoftwareRuleService ruleService;

    @MockitoBean
    private ProhibitedSoftwareFindingService findingService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @MockitoBean
    private DeviceCredentialProvider deviceCredentialProvider;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com"));
    }

    // ── Rule CRUD ─────────────────────────────────────────────────────

    @Test
    void managerCanCreateNameRule() throws Exception {
        allowManager("user-1");
        when(ruleService.create(any(AdminTenantContext.class), any()))
                .thenReturn(ruleResponse());

        mockMvc.perform(post("/api/v1/admin/prohibited-software/rules")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "matchType": "NAME",
                                  "matchMode": "EXACT",
                                  "namePattern": "uTorrent"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.matchType").value("NAME"))
                .andExpect(jsonPath("$.namePattern").value("uTorrent"));
    }

    @Test
    void createReturns400WhenBodyMissingRequiredFields() throws Exception {
        allowManager("user-1");

        mockMvc.perform(post("/api/v1/admin/prohibited-software/rules")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ruleService);
    }

    @Test
    void createReturns400WhenValidatorRejects() throws Exception {
        allowManager("user-1");
        when(ruleService.create(any(AdminTenantContext.class), any()))
                .thenThrow(new IllegalArgumentException(
                        "publisherPattern must be absent when matchType=NAME."));

        mockMvc.perform(post("/api/v1/admin/prohibited-software/rules")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "matchType": "NAME",
                                  "matchMode": "EXACT",
                                  "namePattern": "uTorrent",
                                  "publisherPattern": "Vendor"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        allowManager("user-1");
        when(ruleService.create(any(AdminTenantContext.class), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "An equivalent prohibited-software rule already exists."));

        mockMvc.perform(post("/api/v1/admin/prohibited-software/rules")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "matchType": "NAME",
                                  "matchMode": "EXACT",
                                  "namePattern": "uTorrent"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void listReturnsPaginatedShape() throws Exception {
        allowViewer("user-1");
        when(ruleService.list(any(AdminTenantContext.class), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(ruleResponse()),
                        org.springframework.data.domain.PageRequest.of(0, 25), 1));

        mockMvc.perform(get("/api/v1/admin/prohibited-software/rules").with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(RULE_ID.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void deniedViewerCannotListRules() throws Exception {
        denyAll("user-1");

        mockMvc.perform(get("/api/v1/admin/prohibited-software/rules").with(adminJwt("user-1")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ruleService);
    }

    @Test
    void viewerCannotCreateRule() throws Exception {
        // can_manage denied → 403 even though body is valid.
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module",
                EndpointAdminAuthz.MODULE)).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/prohibited-software/rules")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "matchType": "NAME",
                                  "matchMode": "EXACT",
                                  "namePattern": "uTorrent"
                                }
                                """))
                .andExpect(status().isForbidden());
        verify(ruleService, never()).create(any(), any());
    }

    @Test
    void viewerCannotDeleteRule() throws Exception {
        when(authzService.check("user-1", EndpointAdminAuthz.MANAGER, "module",
                EndpointAdminAuthz.MODULE)).thenReturn(false);

        mockMvc.perform(delete("/api/v1/admin/prohibited-software/rules/{id}", RULE_ID)
                        .with(adminJwt("user-1")))
                .andExpect(status().isForbidden());
        verify(ruleService, never()).delete(any(), any());
    }

    // ── Device-facing findings ────────────────────────────────────────

    @Test
    void viewerCanReadDeviceFindings() throws Exception {
        allowViewer("user-1");
        when(findingService.getDeviceFindings(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(new DeviceProhibitedSoftwareResponse(
                        DEVICE_ID,
                        DeviceProhibitedSoftwareResponse.Status.OK,
                        "UNAUTHORIZED",
                        Instant.parse("2026-05-30T10:00:00Z"),
                        UUID.fromString("99999999-9999-9999-9999-999999999999"),
                        List.of(new ProhibitedSoftwareFindingResponse(
                                RULE_ID, "NAME", "EXACT", "uTorrent",
                                "BitTorrent Inc", "3.5"))));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/prohibited-software",
                        DEVICE_ID).with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.decision").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].matchedName").value("uTorrent"))
                .andExpect(jsonPath("$.findings[0].ruleId").value(RULE_ID.toString()));
    }

    @Test
    void deviceFindingsNoLeakReturns200NoEvaluation() throws Exception {
        // Unknown / cross-tenant device → 200 NO_EVALUATION (no 404, no leak).
        allowViewer("user-1");
        when(findingService.getDeviceFindings(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(DeviceProhibitedSoftwareResponse.noEvaluation(DEVICE_ID));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/prohibited-software",
                        DEVICE_ID).with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_EVALUATION"))
                .andExpect(jsonPath("$.findings.length()").value(0));
    }

    @Test
    void deniedViewerCannotReadDeviceFindings() throws Exception {
        denyAll("user-1");

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/prohibited-software",
                        DEVICE_ID).with(adminJwt("user-1")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(findingService);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void allowViewer(String subject) {
        when(authzService.check(subject, EndpointAdminAuthz.VIEWER, "module",
                EndpointAdminAuthz.MODULE)).thenReturn(true);
    }

    private void allowManager(String subject) {
        when(authzService.check(subject, EndpointAdminAuthz.MANAGER, "module",
                EndpointAdminAuthz.MODULE)).thenReturn(true);
    }

    private void denyAll(String subject) {
        when(authzService.check(eq(subject), any(), eq("module"),
                eq(EndpointAdminAuthz.MODULE))).thenReturn(false);
    }

    private static ProhibitedSoftwareRuleResponse ruleResponse() {
        return new ProhibitedSoftwareRuleResponse(
                RULE_ID, TENANT_ID,
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.EXACT,
                "uTorrent", null, true, "banned",
                "admin@example.com", Instant.parse("2026-05-30T09:00:00Z"),
                "admin@example.com", Instant.parse("2026-05-30T09:00:00Z"), 1L);
    }

    private static RequestPostProcessor adminJwt(String subject) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_endpoint-admin"));
    }
}
