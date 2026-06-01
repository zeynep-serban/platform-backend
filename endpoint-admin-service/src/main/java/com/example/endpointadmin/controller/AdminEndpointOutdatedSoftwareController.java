package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareSnapshotResponse;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareSnapshotSummaryResponse;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareService;
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
 * BE — Outdated-Software admin REST surface (Faz 22.5, AG-036 query API).
 * Mirrors the AG-033 {@link AdminEndpointDeviceHealthController} pattern
 * EXACTLY against the outdated-software snapshots that
 * {@link EndpointOutdatedSoftwareService} ingest writes.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/outdated-software/latest}
 *       — single most-recent snapshot with upgradeable-package facets folded
 *       in. {@code 404} when no snapshot exists for the device, OR when the
 *       requesting tenant has no read access to the device (the repository
 *       query is tenant-scoped, so a cross-tenant request falls into the same
 *       "no snapshot" branch — device existence does not leak).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/outdated-software/history}
 *       — paged summary list (no child arrays) for the history accordion.
 *       Empty page is the canonical no-data answer so the client renders the
 *       "no history yet" empty state without a 404 to special-case.</li>
 * </ul>
 *
 * <p>RBAC: both routes require {@code module:endpoint-admin} {@code can_view}
 * via {@link RequireModule}; no new OpenFGA scope (parity with the
 * device-health query endpoint).
 *
 * <p>Lazy loading: {@code spring.jpa.open-in-view=false} on this service
 * means a controller method that walks the entity's child collection OUTSIDE
 * a transaction would throw {@code LazyInitializationException}. The
 * {@code @Transactional(readOnly = true)} on {@link #getLatest(UUID)} opens
 * the session for the entire request so
 * {@code AdminOutdatedSoftwareSnapshotResponse.from} can fold {@code packages}
 * without explicit fetch-graphs.
 *
 * <p>Pageable cap: {@link #DEFAULT_PAGE_SIZE} / {@link #MAX_PAGE_SIZE} mirror
 * the device-health history view's 20/50 window — a chronological scrub per
 * device, not a fleet search.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointOutdatedSoftwareController {

    /** Default page size when {@code size} is missing or non-positive. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap for the per-page row count. Sizes above this are clamped
     *  silently — the controller does not 400 because the UI typically
     *  requests {@code size=size} where size is a client-side default the
     *  user did not author. */
    static final int MAX_PAGE_SIZE = 50;

    private final EndpointOutdatedSoftwareService outdatedSoftwareService;
    private final EndpointOutdatedSoftwareDiffService outdatedSoftwareDiffService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointOutdatedSoftwareController(
            EndpointOutdatedSoftwareService outdatedSoftwareService,
            EndpointOutdatedSoftwareDiffService outdatedSoftwareDiffService,
            TenantContextResolver tenantContextResolver) {
        this.outdatedSoftwareService = outdatedSoftwareService;
        this.outdatedSoftwareDiffService = outdatedSoftwareDiffService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/outdated-software/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminOutdatedSoftwareSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointOutdatedSoftwareSnapshot snapshot = outdatedSoftwareService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No outdated-software snapshot for device " + deviceId));
        return AdminOutdatedSoftwareSnapshotResponse.from(snapshot);
    }

    @GetMapping("/endpoint-devices/{deviceId}/outdated-software/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public Page<AdminOutdatedSoftwareSnapshotSummaryResponse> getHistory(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                clampPageSize(size));
        return outdatedSoftwareService
                .findHistory(context.tenantId(), deviceId, pageable)
                .map(AdminOutdatedSoftwareSnapshotSummaryResponse::from);
    }

    /**
     * BE-024b — latest-vs-previous outdated-software diff (Faz 22.5 P2-A
     * slice-3). Always returns HTTP 200; status enum encodes the
     * NO_HISTORY/INSUFFICIENT_HISTORY/NO_CHANGE/OK branch. Codex
     * 019e8542 iter-2 AGREE absorb.
     */
    @GetMapping("/endpoint-devices/{deviceId}/outdated-software/diff")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminOutdatedSoftwareDiffResponse getDeviceOutdatedSoftwareDiff(
            @PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return outdatedSoftwareDiffService.diffLatest(context, deviceId);
    }

    /** Clamp the requested page size into [1, {@link #MAX_PAGE_SIZE}]. A zero
     *  or negative request collapses to {@link #DEFAULT_PAGE_SIZE} so the
     *  response is never empty for a reason other than "no data". Anything
     *  above the cap is clamped down silently — the page metadata reports the
     *  actual size so the client can re-render its paginator. */
    static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
