package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointHardwareInventoryDisk;
import com.example.endpointadmin.model.EndpointHardwareInventoryNetworkInterface;
import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointHardwareInventoryService;
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
 * BE-022Q — MockMvc slice test for
 * {@link AdminEndpointHardwareInventoryController} (Faz 22.5.2 query API).
 *
 * <p>Local profile auth bypass; 401 / 403 coverage handled by
 * {@code AdminEndpointAuthorizationSecurityTest} +
 * {@link EndpointAdminAuthorizationAnnotationTest} reflection coverage.
 * This class focuses on:
 *
 * <ul>
 *   <li>200 latest with disks + network interfaces folded into the
 *       response (Codex 019e70c1 plan-time must-fix #4 lazy guard).</li>
 *   <li>404 latest when no snapshot exists (Codex must-fix #7 tenant
 *       isolation: cross-tenant request falls into the same branch).</li>
 *   <li>history paged 200 with summary projection (no children) and
 *       counts surface (Codex must-fix #1 four-DTO plan).</li>
 *   <li>page size clamp: default 20, max 50 (Codex must-fix #5).</li>
 *   <li>response does NOT carry the raw redactedPayload jsonb (Codex
 *       must-fix #3 — only the whitelist DTO fields are visible).</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointHardwareInventoryController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointHardwareInventoryControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointHardwareInventoryService hardwareService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturns200WithFoldedChildren() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(hardwareService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(snapshot()));

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/latest",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.cpuModel").value("Intel(R) Core(TM) i7-12700H"))
                .andExpect(jsonPath("$.cpuCores").value(14))
                .andExpect(jsonPath("$.ramTotalBytes").value(17179869184L))
                .andExpect(jsonPath("$.osName").value("Microsoft Windows 11 Pro"))
                .andExpect(jsonPath("$.manufacturer").value("ContosoCo"))
                .andExpect(jsonPath("$.domainJoined").value(true))
                .andExpect(jsonPath("$.disks.length()").value(1))
                .andExpect(jsonPath("$.disks[0].devicePath").value("C:"))
                .andExpect(jsonPath("$.disks[0].mediaType").value("SSD"))
                .andExpect(jsonPath("$.disks[0].busType").value("NVME"))
                .andExpect(jsonPath("$.networkInterfaces.length()").value(1))
                .andExpect(jsonPath("$.networkInterfaces[0].macAddress")
                        .value("aa:bb:cc:dd:ee:ff"))
                .andExpect(jsonPath("$.networkInterfaces[0].ipAddresses[0]")
                        .value("10.0.0.5"))
                .andExpect(jsonPath("$.probeErrors.length()").value(1))
                .andExpect(jsonPath("$.probeErrors[0].code").value("CIM_TIMEOUT"))
                .andExpect(jsonPath("$.payloadHashSha256").exists())
                // BE-022Q must_fix #3: raw redactedPayload jsonb MUST NOT
                // be present on the response (whitelist DTO only).
                .andExpect(jsonPath("$.redactedPayload").doesNotExist());
    }

    @Test
    void getLatestReturns404WhenNoSnapshot() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(hardwareService.findLatest(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/latest",
                        DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistoryReturnsSummaryPage() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointHardwareInventorySnapshot> page =
                new PageImpl<>(List.of(snapshot()), PageRequest.of(0, 20), 1);
        when(hardwareService.findHistory(eq(TENANT_ID), eq(DEVICE_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/history",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.content[0].cpuModel")
                        .value("Intel(R) Core(TM) i7-12700H"))
                .andExpect(jsonPath("$.content[0].diskCount").value(1))
                .andExpect(jsonPath("$.content[0].networkInterfaceCount").value(1))
                .andExpect(jsonPath("$.content[0].probeErrorCount").value(1))
                // Summary projection: NO children, NO redactedPayload.
                .andExpect(jsonPath("$.content[0].disks").doesNotExist())
                .andExpect(jsonPath("$.content[0].networkInterfaces").doesNotExist())
                .andExpect(jsonPath("$.content[0].redactedPayload").doesNotExist());
    }

    @Test
    void getHistoryClampsRequestedSizeAboveMax() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        // Capture the Pageable the controller hands to the service so
        // we can assert the size was clamped to MAX_PAGE_SIZE=50.
        Page<EndpointHardwareInventorySnapshot> empty =
                new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(hardwareService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 50)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/history",
                        DEVICE_ID)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void getHistoryCollapsesZeroSizeToDefault() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        Page<EndpointHardwareInventorySnapshot> empty =
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(hardwareService.findHistory(
                eq(TENANT_ID), eq(DEVICE_ID),
                argThat((Pageable p) -> p != null && p.getPageSize() == 20)))
                .thenReturn(empty);

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/history",
                        DEVICE_ID)
                        .param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void clampPageSizeHonoursBounds() {
        // Pure unit-style assertion on the package-private helper so
        // the clamping policy is locked in even if a future refactor
        // moves the call site.
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointHardwareInventoryController.clampPageSize(0));
        org.junit.jupiter.api.Assertions.assertEquals(20,
                AdminEndpointHardwareInventoryController.clampPageSize(-1));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                AdminEndpointHardwareInventoryController.clampPageSize(1));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointHardwareInventoryController.clampPageSize(50));
        org.junit.jupiter.api.Assertions.assertEquals(50,
                AdminEndpointHardwareInventoryController.clampPageSize(999));
    }

    // ---------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointHardwareInventorySnapshot snapshot() {
        EndpointHardwareInventorySnapshot snap = new EndpointHardwareInventorySnapshot();
        snap.setId(SNAPSHOT_ID);
        snap.setTenantId(TENANT_ID);
        snap.setDeviceId(DEVICE_ID);
        snap.setSchemaVersion(1);
        snap.setSupported(true);
        snap.setCpuModel("Intel(R) Core(TM) i7-12700H");
        snap.setCpuCores((short) 14);
        snap.setCpuFrequencyMhz(2300);
        snap.setRamTotalBytes(17179869184L);
        snap.setRamAvailableBytes(8589934592L);
        snap.setOsName("Microsoft Windows 11 Pro");
        snap.setOsVersion("10.0.22631");
        snap.setOsArch("64-bit");
        snap.setBiosVendor("Acme BIOS");
        snap.setBiosVersion("1.42.0");
        snap.setManufacturer("ContosoCo");
        snap.setSystemModel("AcmePro 9000");
        snap.setDomainJoined(true);
        snap.setDomainName("corp.example.com");
        snap.setLastBootAt(Instant.parse("2026-05-28T08:15:00Z"));
        snap.setPayloadHashSha256("a".repeat(64));
        snap.setCollectedAt(Instant.parse("2026-05-28T12:00:00Z"));

        EndpointHardwareInventoryDisk disk = new EndpointHardwareInventoryDisk();
        disk.setSnapshot(snap);
        disk.setDevicePath("C:");
        disk.setModel("Samsung 980 PRO");
        disk.setMediaType(EndpointHardwareInventoryDisk.MediaType.SSD);
        disk.setBusType(EndpointHardwareInventoryDisk.BusType.NVME);
        disk.setCapacityBytes(500_000_000_000L);
        disk.setFreeBytes(250_000_000_000L);
        disk.setRemovable(false);
        snap.getDisks().add(disk);

        EndpointHardwareInventoryNetworkInterface nic = new EndpointHardwareInventoryNetworkInterface();
        nic.setSnapshot(snap);
        nic.setName("Intel(R) Wi-Fi 6");
        nic.setMacAddress("aa:bb:cc:dd:ee:ff");
        nic.setInterfaceType(EndpointHardwareInventoryNetworkInterface.InterfaceType.WIFI);
        nic.setLinkState(EndpointHardwareInventoryNetworkInterface.LinkState.UP);
        List<String> ips = new ArrayList<>();
        ips.add("10.0.0.5");
        nic.setIpAddresses(ips);
        snap.getNetworkInterfaces().add(nic);

        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> e = new HashMap<>();
        e.put("code", "CIM_TIMEOUT");
        e.put("summary", "WMI provider stalled");
        errors.add(e);
        snap.setProbeErrors(errors);
        return snap;
    }
}
