package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventorySnapshotResponse;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareInventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BE-020I — Software Inventory admin REST surface (Faz 22.5.3A).
 *
 * <p>Reuses the {@code module:endpoint-admin} {@code can_view} relation via
 * {@code @RequireModule(VIEWER)} — no new OpenFGA scope opened
 * (Codex 019e6ab2 iter-2 plan acceptance).
 *
 * <p>Two routes:
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/software-inventory}
 *       — single device summary + paged items (optional {@code q} substring
 *       filter on display name, {@code publisher} exact match, {@code
 *       installSource} enum filter).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-software-inventory} — fleet-wide
 *       paged snapshot summary; {@code softwareName} presence makes the row
 *       only appear when the device's items contain that app.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointSoftwareInventoryController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final EndpointSoftwareInventoryService inventoryService;
    private final EndpointSoftwareInventoryItemRepository itemRepository;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointSoftwareInventoryController(
            EndpointSoftwareInventoryService inventoryService,
            EndpointSoftwareInventoryItemRepository itemRepository,
            TenantContextResolver tenantContextResolver) {
        this.inventoryService = inventoryService;
        this.itemRepository = itemRepository;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/software-inventory")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public DeviceSoftwareInventoryPayload getDeviceSnapshot(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) SoftwareInstallSource installSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointSoftwareInventorySnapshot snapshot =
                inventoryService.requireDeviceSnapshot(context, deviceId);

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        Page<AdminSoftwareInventoryItemResponse> items = itemRepository
                .pageByTenantDeviceWithFilters(
                        context.tenantId(),
                        deviceId,
                        trimToNull(q),
                        trimToNull(publisher),
                        installSource,
                        pageable)
                .map(AdminSoftwareInventoryItemResponse::from);

        return new DeviceSoftwareInventoryPayload(
                AdminSoftwareInventorySnapshotResponse.from(snapshot),
                items);
    }

    @GetMapping("/endpoint-software-inventory")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public Page<AdminSoftwareInventorySnapshotResponse> listFleetSnapshots(
            @RequestParam(required = false) String softwareName,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) Boolean wingetReady,
            @RequestParam(required = false) Boolean truncated,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        return inventoryService.pageFleet(
                context,
                trimToNull(softwareName),
                trimToNull(publisher),
                wingetReady,
                truncated,
                pageable)
                .map(AdminSoftwareInventorySnapshotResponse::from);
    }

    /**
     * Wire shape for the device-detail endpoint: snapshot summary +
     * paged items.
     */
    public record DeviceSoftwareInventoryPayload(
            AdminSoftwareInventorySnapshotResponse snapshot,
            Page<AdminSoftwareInventoryItemResponse> items
    ) {
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
