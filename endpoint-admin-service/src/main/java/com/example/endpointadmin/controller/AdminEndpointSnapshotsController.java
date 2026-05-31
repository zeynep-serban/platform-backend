package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDeviceHealthLatestEntry;
import com.example.endpointadmin.dto.v1.admin.AdminEndpointLatestSnapshotsResponse;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareLatestEntry;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.BulkLatestSnapshots;
import com.example.endpointadmin.service.EndpointDeviceHealthService;
import com.example.endpointadmin.service.EndpointOutdatedSoftwareService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * BE — fleet-wide bulk latest-snapshots admin REST surface (Faz 22.5,
 * #1146). One round-trip that feeds the device inventory CSV-export v2
 * columns (device-health AG-033 + outdated-software AG-036) instead of an
 * N-per-row client fetch storm.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/snapshots/latest} — the
 *       latest device-health + latest outdated-software snapshot per
 *       device for the caller's tenant; each group is capped with a
 *       per-group truncation flag (see
 *       {@link AdminEndpointLatestSnapshotsResponse}). Tenant-wide (no
 *       device-id list): the device grid + export already operate on the
 *       whole tenant, and a tenant-wide answer is what makes
 *       "device absent from a non-truncated group ⇒ no snapshot"
 *       authoritative — exactly what the export needs so a missing cell is
 *       a real "no evidence", never a "not fetched yet" false absence.</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin} {@code can_view} via
 * {@link RequireModule} (no new OpenFGA scope; parity with the per-device
 * query endpoints).
 *
 * <p>Deliberately NOT {@code @Transactional}: each service
 * {@code findLatestPerDevice(...)} runs its own read-only transaction and
 * returns DETACHED entities; this controller then maps ONLY scalar
 * fields. Keeping the mapping outside any open session means an
 * accidental child-collection access ({@code getDisks()} /
 * {@code getPackages()}) would throw {@code LazyInitializationException}
 * under {@code spring.jpa.open-in-view=false} — a structural backstop for
 * the scalar-only mapping that keeps the fleet fetch free of N+1 (also
 * pinned by the bulk repository test's Hibernate
 * {@code collectionFetchCount == 0} assertion).
 *
 * <p>Cap: {@link #maxSnapshots} (property
 * {@code endpoint-admin.bulk-snapshots.max}, default 10000 to match the
 * web CSV {@code DEFAULT_ROW_CAP} so the v2 columns are available for any
 * export the row cap permits). Tests override it low to exercise
 * truncation.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointSnapshotsController {

    private final EndpointDeviceHealthService deviceHealthService;
    private final EndpointOutdatedSoftwareService outdatedSoftwareService;
    private final TenantContextResolver tenantContextResolver;
    private final int maxSnapshots;

    public AdminEndpointSnapshotsController(
            EndpointDeviceHealthService deviceHealthService,
            EndpointOutdatedSoftwareService outdatedSoftwareService,
            TenantContextResolver tenantContextResolver,
            @Value("${endpoint-admin.bulk-snapshots.max:10000}") int maxSnapshots) {
        this.deviceHealthService = deviceHealthService;
        this.outdatedSoftwareService = outdatedSoftwareService;
        this.tenantContextResolver = tenantContextResolver;
        this.maxSnapshots = maxSnapshots;
    }

    @GetMapping("/endpoint-devices/snapshots/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public AdminEndpointLatestSnapshotsResponse getLatestSnapshots() {
        AdminTenantContext context = tenantContextResolver.resolveRequired();

        BulkLatestSnapshots<EndpointDeviceHealthSnapshot> health =
                deviceHealthService.findLatestPerDevice(context.tenantId(), maxSnapshots);
        BulkLatestSnapshots<EndpointOutdatedSoftwareSnapshot> outdated =
                outdatedSoftwareService.findLatestPerDevice(context.tenantId(), maxSnapshots);

        // Scalar-only mapping OUTSIDE any transaction (see class javadoc).
        List<AdminDeviceHealthLatestEntry> healthEntries = health.snapshots().stream()
                .map(AdminDeviceHealthLatestEntry::from)
                .toList();
        List<AdminOutdatedSoftwareLatestEntry> outdatedEntries = outdated.snapshots().stream()
                .map(AdminOutdatedSoftwareLatestEntry::from)
                .toList();

        return new AdminEndpointLatestSnapshotsResponse(
                healthEntries,
                health.truncated(),
                outdatedEntries,
                outdated.truncated(),
                maxSnapshots);
    }
}
