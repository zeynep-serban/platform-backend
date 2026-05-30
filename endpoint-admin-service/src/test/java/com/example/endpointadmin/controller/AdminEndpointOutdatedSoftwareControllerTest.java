package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE — MockMvc slice test for {@link AdminEndpointOutdatedSoftwareController}
 * (Faz 22.5, AG-036 query API). Mirrors the AG-033
 * {@code AdminEndpointDeviceHealthControllerTest}.
 *
 * <p>Local profile auth bypass; 401 / 403 coverage handled by the
 * authorization annotation reflection coverage. This class focuses on:
 *
 * <ul>
 *   <li>200 latest with package facets folded in (lazy guard).</li>
 *   <li>The package DTO JSON keys are EXACTLY the contract set
 *       {packageId, installedVersion, availableVersion} — the redaction
 *       boundary, machine-asserted (no name/publisher/path/license/url).</li>
 *   <li>The "possibly truncated" signal surfaces when
 *       {@code upgradeCount == maxUpgrade (512)}.</li>
 *   <li>404 latest when no snapshot exists (tenant isolation: a
 *       cross-tenant request falls into the same branch).</li>
 *   <li>history paged 200 with summary projection (no children).</li>
 *   <li>page size clamp: default 20, max 50.</li>
 *   <li>response does NOT carry the raw redactedPayload jsonb.</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointOutdatedSoftwareController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointOutdatedSoftwareControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointOutdatedSoftwareService outdatedSoftwareService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturns200WithFoldedPackagesAndContractKeysOnly() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(outdatedSoftwareService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snapshot()));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.probeComplete").value(true))
                .andExpect(jsonPath("$.upgradeCount").value(2))
                .andExpect(jsonPath("$.upgradeTruncated").value(false))
                .andExpect(jsonPath("$.maxUpgrade").value(512))
                .andExpect(jsonPath("$.possiblyTruncated").value(false))
                .andExpect(jsonPath("$.sourceUsed").value("winget"))
                .andExpect(jsonPath("$.packages.length()").value(2))
                .andExpect(jsonPath("$.packages[0].packageId").value("7zip.7zip"))
                .andExpect(jsonPath("$.packages[0].installedVersion").value("24.09"))
                .andExpect(jsonPath("$.packages[0].availableVersion").value("25.01"))
                // The package DTO JSON keys are EXACTLY the contract set — no
                // forbidden package identifier ever appears on the wire.
                .andExpect(jsonPath("$.packages[0].name").doesNotExist())
                .andExpect(jsonPath("$.packages[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.packages[0].publisher").doesNotExist())
                .andExpect(jsonPath("$.packages[0].installLocation").doesNotExist())
                .andExpect(jsonPath("$.packages[0].license").doesNotExist())
                .andExpect(jsonPath("$.packages[0].downloadUrl").doesNotExist())
                .andExpect(jsonPath("$.packages[0].path").doesNotExist())
                .andExpect(jsonPath("$.payloadHashSha256").exists())
                // Raw redactedPayload jsonb MUST NOT be present.
                .andExpect(jsonPath("$.redactedPayload").doesNotExist());
    }

    @Test
    void getLatestSurfacesPossiblyTruncatedAtCap() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        EndpointOutdatedSoftwareSnapshot snap = snapshot();
        snap.setUpgradeCount(512);
        snap.setMaxUpgrade(512);
        when(outdatedSoftwareService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snap));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upgradeCount").value(512))
                .andExpect(jsonPath("$.possiblyTruncated").value(true));
    }

    @Test
    void getLatestReturns404WhenNoSnapshot() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(outdatedSoftwareService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/latest",
                        DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistoryReturnsSummaryPage() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointOutdatedSoftwareSnapshot> page =
                new PageImpl<>(java.util.List.of(snapshot()), PageRequest.of(0, 20), 1);
        when(outdatedSoftwareService.findHistory(eq(TENANT_ID), eq(DEVICE_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/history",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.content[0].upgradeCount").value(2))
                .andExpect(jsonPath("$.content[0].packageCount").value(2))
                .andExpect(jsonPath("$.content[0].possiblyTruncated").value(false))
                // Summary projection: NO child arrays, NO redactedPayload.
                .andExpect(jsonPath("$.content[0].packages").doesNotExist())
                .andExpect(jsonPath("$.content[0].redactedPayload").doesNotExist());
    }

    @Test
    void getHistoryClampsRequestedSizeAboveMax() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointOutdatedSoftwareSnapshot> empty =
                new PageImpl<>(java.util.List.of(), PageRequest.of(0, 50), 0);
        when(outdatedSoftwareService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 50)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/history",
                        DEVICE_ID)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void getHistoryCollapsesZeroSizeToDefault() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointOutdatedSoftwareSnapshot> empty =
                new PageImpl<>(java.util.List.of(), PageRequest.of(0, 20), 0);
        when(outdatedSoftwareService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 20)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/outdated-software/history",
                        DEVICE_ID)
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void clampPageSizeHonoursBounds() {
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointOutdatedSoftwareController.clampPageSize(0));
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointOutdatedSoftwareController.clampPageSize(-1));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                AdminEndpointOutdatedSoftwareController.clampPageSize(1));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointOutdatedSoftwareController.clampPageSize(50));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointOutdatedSoftwareController.clampPageSize(999));
    }

    // ---------------------------------------------------------------
    // Fixture builders — the "with-upgrades" golden example
    // ---------------------------------------------------------------

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointOutdatedSoftwareSnapshot snapshot() {
        EndpointOutdatedSoftwareSnapshot snap = new EndpointOutdatedSoftwareSnapshot();
        snap.setId(SNAPSHOT_ID);
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(DEVICE_ID);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setUpgradeCount(2);
        snap.setUpgradeTruncated(false);
        snap.setMaxUpgrade(512);
        snap.setSourceUsed("winget");
        snap.setProbeDurationMs(45);
        snap.setPayloadHashSha256("a".repeat(64));
        snap.setCollectedAt(Instant.parse("2026-05-29T12:00:00Z"));

        snap.getPackages().add(pkg(snap, "7zip.7zip", "24.09", "25.01"));
        snap.getPackages().add(pkg(snap, "Microsoft.VisualStudioCode", "1.89.0", "1.91.1"));
        return snap;
    }

    private static EndpointOutdatedSoftwarePackage pkg(
            EndpointOutdatedSoftwareSnapshot snap, String id, String installed, String available) {
        EndpointOutdatedSoftwarePackage p = new EndpointOutdatedSoftwarePackage();
        p.setSnapshot(snap);
        p.setPackageId(id);
        p.setInstalledVersion(installed);
        p.setAvailableVersion(available);
        return p;
    }
}
