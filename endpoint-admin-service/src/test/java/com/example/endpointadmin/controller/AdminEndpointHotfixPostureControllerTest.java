package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointHotfixPostureInstalled;
import com.example.endpointadmin.model.EndpointHotfixPosturePending;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingCategoryCount;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingKb;
import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointHotfixPostureService;
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
 * BE — MockMvc slice test for {@link AdminEndpointHotfixPostureController}
 * (Faz 22.5, AG-037 query API). Mirrors the AG-036
 * {@code AdminEndpointOutdatedSoftwareControllerTest}.
 *
 * <p>Local profile auth bypass; this class focuses on:
 *
 * <ul>
 *   <li>200 latest with installed + pending + pendingByCategory +
 *       agentHealth folded in.</li>
 *   <li>Redaction boundary machine-asserted: forbidden keys (title /
 *       publisher / productCode / installClient / rawRegistry) MUST NOT
 *       appear in any nested JSON path.</li>
 *   <li>"possibly truncated" signals surface when counts hit cap.</li>
 *   <li>404 latest when no snapshot exists (cross-tenant isolation).</li>
 *   <li>history paged 200 with summary projection (no children).</li>
 *   <li>page size clamp: default 20, max 50.</li>
 *   <li>response does NOT carry the raw redactedPayload jsonb.</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointHotfixPostureController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointHotfixPostureControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointHotfixPostureService hotfixPostureService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturns200WithFoldedChildrenAndContractKeysOnly() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(hotfixPostureService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snapshot()));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.probeComplete").value(true))
                .andExpect(jsonPath("$.installedCount").value(1))
                .andExpect(jsonPath("$.maxInstalled").value(512))
                .andExpect(jsonPath("$.installedTruncated").value(false))
                .andExpect(jsonPath("$.installedPossiblyTruncated").value(false))
                .andExpect(jsonPath("$.pendingTotalCount").value(1))
                .andExpect(jsonPath("$.maxPending").value(20))
                .andExpect(jsonPath("$.pendingTruncated").value(false))
                .andExpect(jsonPath("$.pendingPossiblyTruncated").value(false))
                .andExpect(jsonPath("$.installedSourceUsed").value("wua"))
                .andExpect(jsonPath("$.pendingSourceUsed").value("wua"))
                .andExpect(jsonPath("$.healthSourceUsed").value("composite"))
                // Installed children
                .andExpect(jsonPath("$.installedHotfixes.length()").value(1))
                .andExpect(jsonPath("$.installedHotfixes[0].kbId").value("KB5034122"))
                // Pending children (kbIds nested)
                .andExpect(jsonPath("$.pendingUpdates.length()").value(1))
                .andExpect(jsonPath("$.pendingUpdates[0].kbIds[0]").value("KB5036899"))
                .andExpect(jsonPath("$.pendingUpdates[0].primaryCategory").value("SECURITY"))
                .andExpect(jsonPath("$.pendingUpdates[0].severity").value("CRITICAL"))
                // pendingByCategory rollup
                .andExpect(jsonPath("$.pendingByCategory.length()").value(1))
                .andExpect(jsonPath("$.pendingByCategory[0].category").value("SECURITY"))
                .andExpect(jsonPath("$.pendingByCategory[0].count").value(1))
                // Agent health flat scalars
                .andExpect(jsonPath("$.agentHealth.wuaServiceState").value("RUNNING"))
                .andExpect(jsonPath("$.agentHealth.bitsServiceState").value("RUNNING"))
                .andExpect(jsonPath("$.agentHealth.autoUpdatePolicyEnabled").value(true))
                .andExpect(jsonPath("$.agentHealth.notificationLevel").value("4"))
                // Redaction boundary asserts — NO forbidden field anywhere.
                .andExpect(jsonPath("$.installedHotfixes[0].title").doesNotExist())
                .andExpect(jsonPath("$.installedHotfixes[0].productCode").doesNotExist())
                .andExpect(jsonPath("$.installedHotfixes[0].installClient").doesNotExist())
                .andExpect(jsonPath("$.installedHotfixes[0].commandLine").doesNotExist())
                .andExpect(jsonPath("$.pendingUpdates[0].title").doesNotExist())
                .andExpect(jsonPath("$.pendingUpdates[0].vendor").doesNotExist())
                .andExpect(jsonPath("$.pendingUpdates[0].deploymentAction").doesNotExist())
                .andExpect(jsonPath("$.agentHealth.rawRegistry").doesNotExist())
                // Raw redactedPayload jsonb MUST NOT be present.
                .andExpect(jsonPath("$.redactedPayload").doesNotExist());
    }

    @Test
    void getLatestSurfacesPendingPossiblyTruncatedAtCap() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        EndpointHotfixPostureSnapshot snap = snapshot();
        snap.setPendingTotalCount(20);
        snap.setMaxPending(20);
        when(hotfixPostureService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snap));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTotalCount").value(20))
                .andExpect(jsonPath("$.pendingPossiblyTruncated").value(true));
    }

    @Test
    void getLatestSurfacesInstalledPossiblyTruncatedAtCap() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        EndpointHotfixPostureSnapshot snap = snapshot();
        snap.setInstalledCount(512);
        snap.setMaxInstalled(512);
        snap.setInstalledTruncated(true);
        when(hotfixPostureService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snap));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installedCount").value(512))
                .andExpect(jsonPath("$.installedPossiblyTruncated").value(true));
    }

    @Test
    void getLatestReturns404WhenNoSnapshot() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(hotfixPostureService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/latest",
                        DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistoryReturnsSummaryPage() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointHotfixPostureSnapshot> page =
                new PageImpl<>(java.util.List.of(snapshot()), PageRequest.of(0, 20), 1);
        when(hotfixPostureService.findHistory(eq(TENANT_ID), eq(DEVICE_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/history",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.content[0].installedCount").value(1))
                .andExpect(jsonPath("$.content[0].pendingTotalCount").value(1))
                .andExpect(jsonPath("$.content[0].installedChildCount").value(1))
                .andExpect(jsonPath("$.content[0].pendingChildCount").value(1))
                .andExpect(jsonPath("$.content[0].installedPossiblyTruncated").value(false))
                // Summary projection: NO child arrays, NO redactedPayload, NO agentHealth.
                .andExpect(jsonPath("$.content[0].installedHotfixes").doesNotExist())
                .andExpect(jsonPath("$.content[0].pendingUpdates").doesNotExist())
                .andExpect(jsonPath("$.content[0].agentHealth").doesNotExist())
                .andExpect(jsonPath("$.content[0].redactedPayload").doesNotExist());
    }

    @Test
    void getHistoryClampsRequestedSizeAboveMax() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointHotfixPostureSnapshot> empty =
                new PageImpl<>(java.util.List.of(), PageRequest.of(0, 50), 0);
        when(hotfixPostureService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 50)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/history",
                        DEVICE_ID)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void getHistoryCollapsesZeroSizeToDefault() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointHotfixPostureSnapshot> empty =
                new PageImpl<>(java.util.List.of(), PageRequest.of(0, 20), 0);
        when(hotfixPostureService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 20)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/history",
                        DEVICE_ID)
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void clampPageSizeHonoursBounds() {
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointHotfixPostureController.clampPageSize(0));
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointHotfixPostureController.clampPageSize(-1));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                AdminEndpointHotfixPostureController.clampPageSize(1));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointHotfixPostureController.clampPageSize(50));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointHotfixPostureController.clampPageSize(999));
    }

    // ---------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointHotfixPostureSnapshot snapshot() {
        EndpointHotfixPostureSnapshot snap = new EndpointHotfixPostureSnapshot();
        snap.setId(SNAPSHOT_ID);
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(DEVICE_ID);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setInstalledCount(1);
        snap.setMaxInstalled(512);
        snap.setInstalledTruncated(false);
        snap.setPendingTotalCount(1);
        snap.setMaxPending(20);
        snap.setPendingTruncated(false);
        snap.setInstalledSourceUsed("wua");
        snap.setPendingSourceUsed("wua");
        snap.setHealthSourceUsed("composite");
        snap.setProbeDurationMs(410);
        snap.setPayloadHashSha256("a".repeat(64));
        snap.setWuaServiceState("RUNNING");
        snap.setBitsServiceState("RUNNING");
        snap.setLastDetectAt(Instant.parse("2026-05-31T08:00:00Z"));
        snap.setLastInstallAt(Instant.parse("2026-05-30T22:00:00Z"));
        snap.setAutoUpdatePolicyEnabled(true);
        snap.setAutoUpdateEffectiveEnabled(true);
        snap.setNotificationLevel("4");
        snap.setCollectedAt(Instant.parse("2026-06-01T12:00:00Z"));

        EndpointHotfixPostureInstalled installed = new EndpointHotfixPostureInstalled();
        installed.setSnapshot(snap);
        installed.setKbId("KB5034122");
        installed.setInstalledOn(Instant.parse("2026-01-15T00:00:00Z"));
        installed.setDescription("Security Update for Microsoft Windows");
        installed.setRowOrdinal(0);
        snap.getInstalledHotfixes().add(installed);

        EndpointHotfixPosturePending pending = new EndpointHotfixPosturePending();
        pending.setSnapshot(snap);
        pending.setPrimaryCategory("SECURITY");
        pending.setSeverity("CRITICAL");
        pending.setRowOrdinal(0);
        EndpointHotfixPosturePendingKb kb = new EndpointHotfixPosturePendingKb();
        kb.setPending(pending);
        kb.setKbId("KB5036899");
        kb.setRowOrdinal(0);
        pending.getKbs().add(kb);
        snap.getPendingUpdates().add(pending);

        EndpointHotfixPosturePendingCategoryCount cat =
                new EndpointHotfixPosturePendingCategoryCount();
        cat.setSnapshot(snap);
        cat.setCategory("SECURITY");
        cat.setCnt(1);
        cat.setRowOrdinal(0);
        snap.getPendingByCategory().add(cat);

        return snap;
    }
}
