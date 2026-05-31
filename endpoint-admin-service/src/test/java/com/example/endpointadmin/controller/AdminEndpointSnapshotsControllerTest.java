package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.BulkLatestSnapshots;
import com.example.endpointadmin.service.EndpointDeviceHealthService;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareService;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE — MockMvc slice test for {@link AdminEndpointSnapshotsController}
 * (Faz 22.5, #1146 bulk latest-snapshots). Local profile auth bypass;
 * the {@code @RequireModule(can_view)} 401/403 enforcement is covered by
 * the module-wide authorization-annotation reflection test (parity with
 * the per-device query controller tests). This class focuses on:
 *
 * <ul>
 *   <li>200 with both groups mapped to the FLAT entries (deviceId +
 *       scalar summary fields), per-group {@code *Truncated=false}, and
 *       the {@code limit} echoed.</li>
 *   <li>PER-GROUP truncation independence: a truncated group is an empty
 *       list + its flag {@code true}, while the other group still
 *       exports (this is the fail-closed contract that lets the web drop
 *       only the over-cap group's columns).</li>
 *   <li>computed {@code possiblyTruncated} surfaces on the wire.</li>
 *   <li>no raw redactedPayload / child arrays leak.</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointSnapshotsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointSnapshotsControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_A =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEVICE_B =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String PATH =
            "/api/v1/admin/endpoint-devices/snapshots/latest";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointDeviceHealthService deviceHealthService;

    @MockitoBean
    private EndpointOutdatedSoftwareService outdatedSoftwareService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void returnsBothGroupsMappedFlat() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(deviceHealthService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of(healthSnapshot(DEVICE_A, (short) 42))));
        when(outdatedSoftwareService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of(outdatedSnapshot(DEVICE_B, 5, 512))));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(10000))
                .andExpect(jsonPath("$.deviceHealthTruncated").value(false))
                .andExpect(jsonPath("$.deviceHealth.length()").value(1))
                .andExpect(jsonPath("$.deviceHealth[0].deviceId").value(DEVICE_A.toString()))
                .andExpect(jsonPath("$.deviceHealth[0].memoryUsedPercent").value(42))
                .andExpect(jsonPath("$.deviceHealth[0].supported").value(true))
                .andExpect(jsonPath("$.deviceHealth[0].anyLowDisk").value(false))
                // FLAT entry: no child disks array, no raw payload.
                .andExpect(jsonPath("$.deviceHealth[0].disks").doesNotExist())
                .andExpect(jsonPath("$.deviceHealth[0].redactedPayload").doesNotExist())
                .andExpect(jsonPath("$.outdatedSoftwareTruncated").value(false))
                .andExpect(jsonPath("$.outdatedSoftware.length()").value(1))
                .andExpect(jsonPath("$.outdatedSoftware[0].deviceId").value(DEVICE_B.toString()))
                .andExpect(jsonPath("$.outdatedSoftware[0].upgradeCount").value(5))
                .andExpect(jsonPath("$.outdatedSoftware[0].possiblyTruncated").value(false))
                .andExpect(jsonPath("$.outdatedSoftware[0].packages").doesNotExist());
    }

    @Test
    void perGroupTruncationIsIndependent() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        // device-health over cap → empty + truncated; outdated still exports.
        when(deviceHealthService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.overCap());
        when(outdatedSoftwareService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of(outdatedSnapshot(DEVICE_B, 2, 512))));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceHealthTruncated").value(true))
                .andExpect(jsonPath("$.deviceHealth.length()").value(0))
                .andExpect(jsonPath("$.outdatedSoftwareTruncated").value(false))
                .andExpect(jsonPath("$.outdatedSoftware.length()").value(1));
    }

    @Test
    void computesPossiblyTruncatedAtCap() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(deviceHealthService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of()));
        // upgradeCount == maxUpgrade ⇒ possiblyTruncated true (fail-closed).
        when(outdatedSoftwareService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of(outdatedSnapshot(DEVICE_B, 512, 512))));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outdatedSoftware[0].possiblyTruncated").value(true));
    }

    @Test
    void emptyTenantReturnsEmptyGroups() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(deviceHealthService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of()));
        when(outdatedSoftwareService.findLatestPerDevice(eq(TENANT_ID), anyInt()))
                .thenReturn(BulkLatestSnapshots.of(List.of()));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceHealth.length()").value(0))
                .andExpect(jsonPath("$.deviceHealthTruncated").value(false))
                .andExpect(jsonPath("$.outdatedSoftware.length()").value(0))
                .andExpect(jsonPath("$.outdatedSoftwareTruncated").value(false));
    }

    // ── fixtures (plain entities; services are mocked, nothing persisted) ──

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointDeviceHealthSnapshot healthSnapshot(UUID deviceId, short memoryUsedPercent) {
        EndpointDeviceHealthSnapshot snap = new EndpointDeviceHealthSnapshot();
        snap.setId(UUID.randomUUID());
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(deviceId);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setAnyLowDisk(false);
        snap.setMemoryUsedPercent(memoryUsedPercent);
        snap.setMemoryHighPressure(false);
        snap.setUptimeDays(3);
        snap.setLongUptimeWarning(false);
        snap.setCollectedAt(Instant.parse("2026-05-30T12:00:00Z"));
        return snap;
    }

    private EndpointOutdatedSoftwareSnapshot outdatedSnapshot(UUID deviceId, int upgradeCount, int maxUpgrade) {
        EndpointOutdatedSoftwareSnapshot snap = new EndpointOutdatedSoftwareSnapshot();
        snap.setId(UUID.randomUUID());
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(deviceId);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setUpgradeCount(upgradeCount);
        snap.setUpgradeTruncated(false);
        snap.setMaxUpgrade(maxUpgrade);
        snap.setCollectedAt(Instant.parse("2026-05-30T12:00:00Z"));
        return snap;
    }
}
