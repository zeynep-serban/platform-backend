package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightEvidence;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstalledState;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointInstallPreflightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-021A — MockMvc slice test for
 * {@link AdminInstallPreflightController} (Faz 22.5).
 *
 * <p>Local profile auth bypass; 401 / 403 paths covered by the
 * existing repo-wide
 * {@code EndpointAdminAuthorizationAnnotationTest} reflection
 * coverage. This class focuses on Codex 019e6b88 HTTP-semantics:
 *
 * <ul>
 *   <li>{@code 200 + PASS body} when every gate satisfied</li>
 *   <li>{@code 200 + BLOCK body} for business-ineligible states
 *       (catalog draft, device offline, inventory missing, etc.)</li>
 *   <li>{@code 200 + WARN body} when only non-blocking reasons fire</li>
 *   <li>{@code 404} when device or catalog item is not visible to
 *       the caller's tenant (thrown by the service)</li>
 *   <li>{@code 400} when the {@code catalogItemId} query parameter
 *       is missing or blank</li>
 * </ul>
 */
@WebMvcTest(AdminInstallPreflightController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminInstallPreflightControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATALOG_ITEM_UUID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointInstallPreflightService preflightService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void passDecisionReturns200WithEmptyReasons() throws Exception {
        bindTenantContext();
        when(preflightService.evaluate(
                any(AdminTenantContext.class),
                eq(DEVICE_ID),
                eq("7zip.7zip")))
                .thenReturn(buildResponse(InstallPreflightDecision.PASS,
                        InstalledState.NOT_INSTALLED,
                        List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID)
                        .param("catalogItemId", "7zip.7zip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PASS"))
                .andExpect(jsonPath("$.catalogItemId").value("7zip.7zip"))
                .andExpect(jsonPath("$.installedState").value("NOT_INSTALLED"))
                .andExpect(jsonPath("$.blockingReasons").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void blockDecisionReturns200WithReasons() throws Exception {
        bindTenantContext();
        when(preflightService.evaluate(
                any(AdminTenantContext.class),
                eq(DEVICE_ID),
                eq("7zip.7zip")))
                .thenReturn(buildResponse(InstallPreflightDecision.BLOCK,
                        InstalledState.UNKNOWN,
                        List.of("inventory_missing", "winget_egress_missing"),
                        List.of("inventory_missing", "winget_egress_missing"),
                        List.of(),
                        List.of("Run COLLECT_INVENTORY to ingest a software snapshot first.",
                                "Run COLLECT_INVENTORY with includeWinGetEgress=true to capture AG-026A evidence.")));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID)
                        .param("catalogItemId", "7zip.7zip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.blockingReasons[0]").value("inventory_missing"))
                .andExpect(jsonPath("$.blockingReasons[1]").value("winget_egress_missing"))
                .andExpect(jsonPath("$.requirements", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.installedState").value("UNKNOWN"));
    }

    @Test
    void warnDecisionReturns200WithWarningsList() throws Exception {
        bindTenantContext();
        when(preflightService.evaluate(
                any(AdminTenantContext.class),
                eq(DEVICE_ID),
                eq("7zip.7zip")))
                .thenReturn(buildResponse(InstallPreflightDecision.WARN,
                        InstalledState.NOT_INSTALLED,
                        List.of("inventory_stale"),
                        List.of(),
                        List.of("inventory_stale"),
                        List.of()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID)
                        .param("catalogItemId", "7zip.7zip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("WARN"))
                .andExpect(jsonPath("$.warnings[0]").value("inventory_stale"))
                .andExpect(jsonPath("$.blockingReasons").isEmpty());
    }

    @Test
    void deviceNotFoundReturns404() throws Exception {
        bindTenantContext();
        when(preflightService.evaluate(
                any(AdminTenantContext.class),
                eq(DEVICE_ID),
                eq("7zip.7zip")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device not found."));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID)
                        .param("catalogItemId", "7zip.7zip"))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogItemNotFoundReturns404() throws Exception {
        bindTenantContext();
        when(preflightService.evaluate(
                any(AdminTenantContext.class),
                eq(DEVICE_ID),
                eq("Unknown.Pkg")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Catalog item not found."));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID)
                        .param("catalogItemId", "Unknown.Pkg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingCatalogItemIdReturns400() throws Exception {
        bindTenantContext();
        // Spring MVC rejects a missing required @RequestParam before
        // the controller method runs — status is 400.
        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/install-preflight",
                        DEVICE_ID))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers

    private void bindTenantContext() {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "test-admin"));
    }

    private static InstallPreflightResponse buildResponse(
            InstallPreflightDecision decision,
            InstalledState installedState,
            List<String> reasons,
            List<String> blocking,
            List<String> warnings,
            List<String> requirements) {
        InstallPreflightEvidence evidence = new InstallPreflightEvidence(
                null, // inventorySnapshotId
                null, // inventorySnapshotRowVersion
                null, // inventoryUpdatedAt
                null, // summaryCollectedAt
                null, // appsCollectedAt
                null, // latestSummaryCommandResultId
                null, // latestFullCommandResultId
                null, // latestWingetEgressCommandResultId
                null, // wingetEgressCollectedAt
                1,    // wingetEgressSchemaVersion
                1L,   // catalogRowVersion
                Instant.parse("2026-05-28T11:00:00Z"));
        return new InstallPreflightResponse(
                decision,
                "7zip.7zip",
                CATALOG_ITEM_UUID,
                DEVICE_ID,
                Instant.parse("2026-05-28T12:00:00Z"),
                installedState,
                evidence,
                reasons,
                blocking,
                warnings,
                requirements);
    }
}
