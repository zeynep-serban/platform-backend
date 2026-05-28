package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminHardwareInventorySnapshotResponse;
import com.example.endpointadmin.dto.v1.admin.AdminHardwareInventorySnapshotSummaryResponse;
import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointHardwareInventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BE-022Q — Hardware Inventory admin REST surface (Faz 22.5.2 query
 * API). Codex {@code 019e70c1} plan-time AGREE.
 *
 * <p>WEB-013 prerequisite: the frontend hardware view needs a backend
 * read surface to consume the snapshots BE-022 ingest writes. Two
 * routes mirror the BE-020I software-inventory pattern:
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/latest}
 *       — single most-recent snapshot with disks + network interfaces.
 *       {@code 404} when no snapshot exists for the device, OR when
 *       the requesting tenant has no read access to the device (the
 *       repository query is tenant-scoped, so a cross-tenant request
 *       falls into the same "no snapshot" branch — device existence
 *       does not leak).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/hardware-inventory/history}
 *       — paged summary list (no child arrays) for the WEB-013
 *       accordion. Empty page is the canonical no-data answer here so
 *       the client can render the "no history yet" empty state without
 *       a 404 status to special-case.</li>
 * </ul>
 *
 * <p>RBAC: both routes require {@code module:endpoint-admin}
 * {@code can_view} via {@link RequireModule}; no new OpenFGA scope.
 *
 * <p>Lazy loading: {@code spring.jpa.open-in-view=false} on this
 * service means a controller method that walks the entity's child
 * collections OUTSIDE a transaction would throw
 * {@code LazyInitializationException}. The {@code @Transactional(readOnly
 * = true)} on {@link #getLatest(UUID)} opens the session for the
 * entire request so {@code AdminHardwareInventorySnapshotResponse.from}
 * can fold {@code disks} + {@code networkInterfaces} without explicit
 * fetch-graphs (Codex 019e70c1 plan-time must-fix #4).
 *
 * <p>Pageable cap: {@link #DEFAULT_PAGE_SIZE} / {@link #MAX_PAGE_SIZE}
 * are intentionally narrower than BE-020I (which uses 50/200). The
 * hardware history view is a chronological scrub, not a fleet search,
 * so a 20/50 window is the right ergonomic for a per-device accordion
 * (Codex 019e70c1 plan-time must-fix #5).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointHardwareInventoryController {

    /** Default page size when {@code size} is missing or non-positive. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap for the per-page row count. Sizes above this are
     *  clamped silently — the controller does not 400 because the UI
     *  typically requests {@code size=size} where size is a client-side
     *  default the user did not author. */
    static final int MAX_PAGE_SIZE = 50;

    private final EndpointHardwareInventoryService hardwareService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointHardwareInventoryController(
            EndpointHardwareInventoryService hardwareService,
            TenantContextResolver tenantContextResolver) {
        this.hardwareService = hardwareService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/hardware-inventory/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminHardwareInventorySnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointHardwareInventorySnapshot snapshot = hardwareService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No hardware inventory snapshot for device " + deviceId));
        return AdminHardwareInventorySnapshotResponse.from(snapshot);
    }

    @GetMapping("/endpoint-devices/{deviceId}/hardware-inventory/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public Page<AdminHardwareInventorySnapshotSummaryResponse> getHistory(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                clampPageSize(size));
        return hardwareService
                .findHistory(context.tenantId(), deviceId, pageable)
                .map(AdminHardwareInventorySnapshotSummaryResponse::from);
    }

    /** Clamp the requested page size into [1, {@link #MAX_PAGE_SIZE}]. A
     *  zero or negative request collapses to {@link #DEFAULT_PAGE_SIZE}
     *  so the response is never empty for a reason other than "no data".
     *  Anything above the cap is clamped down silently — the page metadata
     *  reports the actual size so the client can re-render its slider /
     *  paginator accordingly. */
    static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
