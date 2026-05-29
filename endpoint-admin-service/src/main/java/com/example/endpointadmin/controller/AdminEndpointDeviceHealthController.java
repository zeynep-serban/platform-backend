package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDeviceHealthSnapshotResponse;
import com.example.endpointadmin.dto.v1.admin.AdminDeviceHealthSnapshotSummaryResponse;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDeviceHealthService;
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
 * BE — Device-Health admin REST surface (Faz 22.5, AG-033 query API).
 * Mirrors the BE-022Q {@code AdminEndpointHardwareInventoryController}
 * pattern EXACTLY against the device-health snapshots that
 * {@link EndpointDeviceHealthService} ingest writes.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/device-health/latest}
 *       — single most-recent snapshot with fixed-disk facets folded in.
 *       {@code 404} when no snapshot exists for the device, OR when the
 *       requesting tenant has no read access to the device (the
 *       repository query is tenant-scoped, so a cross-tenant request
 *       falls into the same "no snapshot" branch — device existence does
 *       not leak).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/device-health/history}
 *       — paged summary list (no child arrays) for the history accordion.
 *       Empty page is the canonical no-data answer so the client renders
 *       the "no history yet" empty state without a 404 to special-case.</li>
 * </ul>
 *
 * <p>RBAC: both routes require {@code module:endpoint-admin}
 * {@code can_view} via {@link RequireModule}; no new OpenFGA scope.
 *
 * <p>Lazy loading: {@code spring.jpa.open-in-view=false} on this service
 * means a controller method that walks the entity's child collection
 * OUTSIDE a transaction would throw {@code LazyInitializationException}.
 * The {@code @Transactional(readOnly = true)} on {@link #getLatest(UUID)}
 * opens the session for the entire request so
 * {@code AdminDeviceHealthSnapshotResponse.from} can fold {@code disks}
 * without explicit fetch-graphs (parity with BE-022Q must-fix #4).
 *
 * <p>Pageable cap: {@link #DEFAULT_PAGE_SIZE} / {@link #MAX_PAGE_SIZE}
 * mirror the hardware history view's 20/50 window — a chronological scrub
 * per device, not a fleet search.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointDeviceHealthController {

    /** Default page size when {@code size} is missing or non-positive. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap for the per-page row count. Sizes above this are clamped
     *  silently — the controller does not 400 because the UI typically
     *  requests {@code size=size} where size is a client-side default the
     *  user did not author. */
    static final int MAX_PAGE_SIZE = 50;

    private final EndpointDeviceHealthService deviceHealthService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDeviceHealthController(
            EndpointDeviceHealthService deviceHealthService,
            TenantContextResolver tenantContextResolver) {
        this.deviceHealthService = deviceHealthService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/device-health/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminDeviceHealthSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointDeviceHealthSnapshot snapshot = deviceHealthService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No device-health snapshot for device " + deviceId));
        return AdminDeviceHealthSnapshotResponse.from(snapshot);
    }

    @GetMapping("/endpoint-devices/{deviceId}/device-health/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public Page<AdminDeviceHealthSnapshotSummaryResponse> getHistory(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                clampPageSize(size));
        return deviceHealthService
                .findHistory(context.tenantId(), deviceId, pageable)
                .map(AdminDeviceHealthSnapshotSummaryResponse::from);
    }

    /** Clamp the requested page size into [1, {@link #MAX_PAGE_SIZE}]. A
     *  zero or negative request collapses to {@link #DEFAULT_PAGE_SIZE} so
     *  the response is never empty for a reason other than "no data".
     *  Anything above the cap is clamped down silently — the page metadata
     *  reports the actual size so the client can re-render its paginator. */
    static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
