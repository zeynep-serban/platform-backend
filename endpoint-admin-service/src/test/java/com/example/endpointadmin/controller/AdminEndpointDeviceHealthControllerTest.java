package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointDeviceHealthDisk;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDeviceHealthService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * BE — MockMvc slice test for {@link AdminEndpointDeviceHealthController}
 * (Faz 22.5, AG-033 query API). Mirrors the BE-022Q
 * {@code AdminEndpointHardwareInventoryControllerTest}.
 *
 * <p>Local profile auth bypass; 401 / 403 coverage handled by the
 * authorization annotation reflection coverage. This class focuses on:
 *
 * <ul>
 *   <li>200 latest with fixed-disk facets folded in (lazy guard).</li>
 *   <li>404 latest when no snapshot exists (tenant isolation: a
 *       cross-tenant request falls into the same branch).</li>
 *   <li>history paged 200 with summary projection (no children).</li>
 *   <li>page size clamp: default 20, max 50.</li>
 *   <li>response does NOT carry the raw redactedPayload jsonb.</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointDeviceHealthController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointDeviceHealthControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointDeviceHealthService deviceHealthService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturns200WithFoldedDisks() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(deviceHealthService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snapshot()));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/device-health/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.probeComplete").value(true))
                .andExpect(jsonPath("$.anyLowDisk").value(true))
                .andExpect(jsonPath("$.memoryUsedPercent").value(95))
                .andExpect(jsonPath("$.memoryHighPressure").value(true))
                .andExpect(jsonPath("$.uptimeDays").value(33))
                .andExpect(jsonPath("$.longUptimeWarning").value(true))
                .andExpect(jsonPath("$.sourceUsed").value("win32"))
                .andExpect(jsonPath("$.fixedDiskCount").value(1))
                .andExpect(jsonPath("$.disks.length()").value(1))
                .andExpect(jsonPath("$.disks[0].driveLetter").value("C:"))
                .andExpect(jsonPath("$.disks[0].freePercent").value(1))
                .andExpect(jsonPath("$.disks[0].lowDiskWarning").value(true))
                // No forbidden disk identifier ever appears on the wire.
                .andExpect(jsonPath("$.disks[0].volumeLabel").doesNotExist())
                .andExpect(jsonPath("$.disks[0].serialNumber").doesNotExist())
                .andExpect(jsonPath("$.disks[0].fileSystem").doesNotExist())
                .andExpect(jsonPath("$.payloadHashSha256").exists())
                // Raw redactedPayload jsonb MUST NOT be present.
                .andExpect(jsonPath("$.redactedPayload").doesNotExist());
    }

    @Test
    void getLatestReturns404WhenNoSnapshot() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(deviceHealthService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/device-health/latest",
                        DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistoryReturnsSummaryPage() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointDeviceHealthSnapshot> page =
                new PageImpl<>(List.of(snapshot()), PageRequest.of(0, 20), 1);
        when(deviceHealthService.findHistory(eq(TENANT_ID), eq(DEVICE_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/device-health/history",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.content[0].anyLowDisk").value(true))
                .andExpect(jsonPath("$.content[0].memoryHighPressure").value(true))
                .andExpect(jsonPath("$.content[0].diskCount").value(1))
                .andExpect(jsonPath("$.content[0].fixedDiskCount").value(1))
                // Summary projection: NO child arrays, NO redactedPayload.
                .andExpect(jsonPath("$.content[0].disks").doesNotExist())
                .andExpect(jsonPath("$.content[0].redactedPayload").doesNotExist());
    }

    @Test
    void getHistoryClampsRequestedSizeAboveMax() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointDeviceHealthSnapshot> empty =
                new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(deviceHealthService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 50)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/device-health/history",
                        DEVICE_ID)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void getHistoryCollapsesZeroSizeToDefault() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointDeviceHealthSnapshot> empty =
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(deviceHealthService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 20)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/device-health/history",
                        DEVICE_ID)
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void clampPageSizeHonoursBounds() {
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointDeviceHealthController.clampPageSize(0));
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointDeviceHealthController.clampPageSize(-1));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                AdminEndpointDeviceHealthController.clampPageSize(1));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointDeviceHealthController.clampPageSize(50));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointDeviceHealthController.clampPageSize(999));
    }

    // ---------------------------------------------------------------
    // Fixture builders — the "warning" golden example
    // ---------------------------------------------------------------

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointDeviceHealthSnapshot snapshot() {
        EndpointDeviceHealthSnapshot snap = new EndpointDeviceHealthSnapshot();
        snap.setId(SNAPSHOT_ID);
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(DEVICE_ID);
        snap.setSchemaVersion((short) 1);
        snap.setSupported(true);
        snap.setProbeComplete(true);
        snap.setAnyLowDisk(true);
        snap.setFixedDiskCount(1);
        snap.setFixedDisksTruncated(false);
        snap.setMaxFixedDisks(64);
        snap.setMemoryUsedPercent((short) 95);
        snap.setMemoryHighPressure(true);
        snap.setUptimeDays(33);
        snap.setUptimeSeconds(2851200L);
        snap.setLastBootEpochSec(1745683200L);
        snap.setLongUptimeWarning(true);
        snap.setSourceUsed("win32");
        snap.setProbeDurationMs(18);
        snap.setPayloadHashSha256("a".repeat(64));
        snap.setCollectedAt(Instant.parse("2026-05-28T12:00:00Z"));

        EndpointDeviceHealthDisk disk = new EndpointDeviceHealthDisk();
        disk.setSnapshot(snap);
        disk.setDriveLetter("C:");
        disk.setTotalBytes(536870912000L);
        disk.setFreeBytes(5368709120L);
        disk.setFreePercent((short) 1);
        disk.setLowDiskWarning(true);
        snap.getDisks().add(disk);

        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> e = new HashMap<>();
        e.put("code", "DISK_ENUM_FAILED");
        e.put("summary", "one volume could not be enumerated");
        errors.add(e);
        snap.setProbeErrors(errors);
        return snap;
    }
}
