package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
 * BE-020I — MockMvc slice test for
 * {@link AdminEndpointSoftwareInventoryController} (Faz 22.5.3A).
 *
 * <p>Local profile auth bypass; 401 / 403 paths covered by
 * {@code AdminEndpointAuthorizationSecurityTest} +
 * {@link EndpointAdminAuthorizationAnnotationTest} reflection coverage.
 * This class focuses on the wire-shape + status mapping (200, 404, 400 for
 * invalid query params).
 */
@WebMvcTest(AdminEndpointSoftwareInventoryController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointSoftwareInventoryControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointSoftwareInventoryService inventoryService;

    @MockitoBean
    private EndpointSoftwareInventoryItemRepository itemRepository;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getDeviceSnapshotReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        EndpointSoftwareInventorySnapshot snapshot = snapshot();
        Page<EndpointSoftwareInventoryItem> items =
                new PageImpl<>(List.of(item()));
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(inventoryService.requireDeviceSnapshot(context, DEVICE_ID))
                .thenReturn(snapshot);
        when(itemRepository.pageByTenantDeviceWithFilters(
                eq(TENANT_ID), eq(DEVICE_ID),
                eq((String) null), eq((String) null),
                eq((SoftwareInstallSource) null), any()))
                .thenReturn(items);

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/software-inventory",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.snapshot.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.snapshot.appsAvailable").value(true))
                .andExpect(jsonPath("$.items.content[0].displayName").value("7-Zip"));
    }

    @Test
    void getDeviceSnapshotReturns404WhenServiceThrowsNotFound() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(inventoryService.requireDeviceSnapshot(context, DEVICE_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Software inventory snapshot not found for device."));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/software-inventory",
                        DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDeviceSnapshotAppliesQueryFilters() throws Exception {
        AdminTenantContext context = adminContext();
        EndpointSoftwareInventorySnapshot snapshot = snapshot();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(inventoryService.requireDeviceSnapshot(context, DEVICE_ID))
                .thenReturn(snapshot);
        when(itemRepository.pageByTenantDeviceWithFilters(
                eq(TENANT_ID), eq(DEVICE_ID),
                eq("Firefox"), eq("Mozilla"),
                eq(SoftwareInstallSource.HKLM), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/software-inventory",
                        DEVICE_ID)
                        .param("q", "Firefox")
                        .param("publisher", "Mozilla")
                        .param("installSource", "HKLM"))
                .andExpect(status().isOk());
    }

    @Test
    void getDeviceSnapshotReturns400OnInvalidInstallSourceEnum() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/software-inventory",
                        DEVICE_ID)
                        .param("installSource", "HKCU"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFleetSnapshotsReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(inventoryService.pageFleet(
                eq(context), eq((String) null), eq((String) null),
                eq((Boolean) null), eq((Boolean) null), any()))
                .thenReturn(new PageImpl<>(List.of(snapshot())));

        mockMvc.perform(get("/api/v1/admin/endpoint-software-inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.content[0].deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.content[0].appsAvailable").value(true));
    }

    @Test
    void listFleetSnapshotsAppliesSoftwareNameFilter() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(inventoryService.pageFleet(
                eq(context), eq("7-Zip"), eq("Igor Pavlov"),
                eq(Boolean.TRUE), eq(Boolean.FALSE), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/endpoint-software-inventory")
                        .param("softwareName", "7-Zip")
                        .param("publisher", "Igor Pavlov")
                        .param("wingetReady", "true")
                        .param("truncated", "false"))
                .andExpect(status().isOk());
    }

    @Test
    void listFleetSnapshotsReturns400OnNonNumericPage() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);

        mockMvc.perform(get("/api/v1/admin/endpoint-software-inventory")
                        .param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------
    // Helpers

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointSoftwareInventorySnapshot snapshot() {
        EndpointSoftwareInventorySnapshot s =
                new EndpointSoftwareInventorySnapshot();
        try {
            java.lang.reflect.Field idField = s.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, SNAPSHOT_ID);
            java.lang.reflect.Field createdField =
                    s.getClass().getDeclaredField("createdAt");
            createdField.setAccessible(true);
            createdField.set(s, Instant.parse("2026-05-27T10:00:00Z"));
            java.lang.reflect.Field updatedField =
                    s.getClass().getDeclaredField("updatedAt");
            updatedField.setAccessible(true);
            updatedField.set(s, Instant.parse("2026-05-27T11:00:00Z"));
            java.lang.reflect.Field versionField =
                    s.getClass().getDeclaredField("version");
            versionField.setAccessible(true);
            versionField.set(s, 0L);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        s.setTenantId(TENANT_ID);
        com.example.endpointadmin.model.EndpointDevice device =
                new com.example.endpointadmin.model.EndpointDevice();
        device.setTenantId(TENANT_ID);
        device.setHostname("PC-A");
        try {
            java.lang.reflect.Field deviceId = device.getClass()
                    .getDeclaredField("id");
            deviceId.setAccessible(true);
            deviceId.set(device, DEVICE_ID);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        s.setDevice(device);
        s.setSchemaVersion(1);
        s.setSupported(true);
        s.setAppCount(2);
        s.setAppsStoredCount(2);
        s.setWingetReady(Boolean.TRUE);
        s.setAppsAvailable(true);
        return s;
    }

    private EndpointSoftwareInventoryItem item() {
        EndpointSoftwareInventoryItem item = new EndpointSoftwareInventoryItem();
        try {
            java.lang.reflect.Field idField =
                    item.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        item.setTenantId(TENANT_ID);
        item.setDeviceId(DEVICE_ID);
        item.setDisplayName("7-Zip");
        item.setDisplayVersion("24.07");
        item.setPublisher("Igor Pavlov");
        item.setInstallSource(SoftwareInstallSource.HKLM);
        item.setUninstallStringPresent(true);
        return item;
    }
}
