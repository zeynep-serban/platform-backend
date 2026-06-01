package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminHotfixPostureSnapshotResponse;
import com.example.endpointadmin.dto.v1.admin.AdminHotfixPostureSnapshotSummaryResponse;
import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointHotfixPostureService;
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
 * BE — Hotfix-Posture admin REST surface (Faz 22.5, AG-037 query API).
 * Mirrors the AG-036 {@link AdminEndpointOutdatedSoftwareController}
 * pattern EXACTLY against the hotfix-posture snapshots that
 * {@link EndpointHotfixPostureService} ingest writes.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/latest}
 *       — single most-recent snapshot with installed+pending+
 *       pendingByCategory children + flat agent-health folded in.
 *       {@code 404} when no snapshot exists for the device, OR when the
 *       requesting tenant has no read access to the device (the
 *       repository query is tenant-scoped, so a cross-tenant request
 *       falls into the same "no snapshot" branch — device existence
 *       does not leak).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/hotfix-posture/history}
 *       — paged summary list (no child arrays) for the history
 *       accordion. Empty page is the canonical no-data answer so the
 *       client renders the "no history yet" empty state without a 404
 *       to special-case.</li>
 * </ul>
 *
 * <p>RBAC: both routes require {@code module:endpoint-admin}
 * {@code can_view} via {@link RequireModule}; no new OpenFGA scope
 * (parity with the device-health + outdated-software query
 * endpoints).
 *
 * <p>Lazy loading: {@code spring.jpa.open-in-view=false} on this
 * service means a controller method that walks the entity's child
 * collections OUTSIDE a transaction would throw
 * {@code LazyInitializationException}. The
 * {@code @Transactional(readOnly = true)} on {@link #getLatest(UUID)}
 * opens the session for the entire request so
 * {@link AdminHotfixPostureSnapshotResponse#from} can fold the children
 * + pending→kbs grand-children without explicit fetch-graphs.
 *
 * <p>Pageable cap: {@link #DEFAULT_PAGE_SIZE} / {@link #MAX_PAGE_SIZE}
 * mirror the AG-036 + AG-033 history view's 20/50 window — a
 * chronological scrub per device, not a fleet search.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointHotfixPostureController {

    /** Default page size when {@code size} is missing or non-positive. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap for the per-page row count. Sizes above this are
     *  clamped silently. */
    static final int MAX_PAGE_SIZE = 50;

    private final EndpointHotfixPostureService hotfixPostureService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointHotfixPostureController(
            EndpointHotfixPostureService hotfixPostureService,
            TenantContextResolver tenantContextResolver) {
        this.hotfixPostureService = hotfixPostureService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/hotfix-posture/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminHotfixPostureSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointHotfixPostureSnapshot snapshot = hotfixPostureService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No hotfix-posture snapshot for device " + deviceId));
        return AdminHotfixPostureSnapshotResponse.from(snapshot);
    }

    @GetMapping("/endpoint-devices/{deviceId}/hotfix-posture/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public Page<AdminHotfixPostureSnapshotSummaryResponse> getHistory(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                clampPageSize(size));
        return hotfixPostureService
                .findHistory(context.tenantId(), deviceId, pageable)
                .map(AdminHotfixPostureSnapshotSummaryResponse::from);
    }

    /** Clamp the requested page size into [1, {@link #MAX_PAGE_SIZE}]. */
    static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
