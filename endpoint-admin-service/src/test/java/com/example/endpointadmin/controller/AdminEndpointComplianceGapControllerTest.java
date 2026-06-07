package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.compliancegap.ComplianceGapResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.ComplianceGapType;
import com.example.endpointadmin.service.EndpointComplianceGapService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller wire-compatibility tests for compliance gap filters.
 *
 * <p>Local profile auth bypass; authorization reflection tests cover
 * {@code @RequireModule}. This class locks the web-client contract that sends
 * {@code gapTypes=rdp_enabled,pending_security_updates} while preserving the
 * older repeatable {@code gapType=} query parameter.
 */
@WebMvcTest(AdminEndpointComplianceGapController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointComplianceGapControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointComplianceGapService gapService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void acceptsPluralCommaSeparatedGapTypesFromWebClient() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(gapService.findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED,
                        ComplianceGapType.PENDING_SECURITY_UPDATES)),
                isNull(), eq(1), eq(50)))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap")
                        .param("gapTypes",
                                "rdp_enabled,pending_security_updates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(gapService).findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED,
                        ComplianceGapType.PENDING_SECURITY_UPDATES)),
                isNull(), eq(1), eq(50));
    }

    @Test
    void acceptsRepeatableSingularGapTypeForBackwardsCompatibility() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(gapService.findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED,
                        ComplianceGapType.PENDING_SECURITY_UPDATES)),
                isNull(), eq(1), eq(50)))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap")
                        .param("gapType", "rdp_enabled",
                                "pending_security_updates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(gapService).findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED,
                        ComplianceGapType.PENDING_SECURITY_UPDATES)),
                isNull(), eq(1), eq(50));
    }

    @Test
    void trimsPluralAliasTokensAndIgnoresBlankTokens() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(gapService.findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED)),
                isNull(), eq(1), eq(50)))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap")
                        .param("gapTypes", " rdp_enabled, , "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(gapService).findGaps(
                eq(TENANT_ID),
                argThat(types -> containsExactly(types,
                        ComplianceGapType.RDP_ENABLED)),
                isNull(), eq(1), eq(50));
    }

    @Test
    void rejectsUnknownGapTypeInPluralAlias() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/compliance-gap")
                        .param("gapTypes", "rdp_enabled,unknown_gap"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gapService);
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private ComplianceGapResponse emptyResponse() {
        return new ComplianceGapResponse(
                List.of(),
                0,
                1,
                50,
                Map.of(
                        "gapTypes", List.of(),
                        "freshnessWindow", "PT168H",
                        "page", 1,
                        "pageSize", 50),
                Instant.parse("2026-06-08T00:00:00Z"));
    }

    private static boolean containsExactly(Set<ComplianceGapType> actual,
                                           ComplianceGapType... expected) {
        return actual != null && actual.equals(Set.of(expected));
    }
}
