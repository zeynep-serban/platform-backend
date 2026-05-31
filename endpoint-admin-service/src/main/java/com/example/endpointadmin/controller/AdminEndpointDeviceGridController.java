package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder;
import com.example.endpointadmin.grid.DeviceGridQueryRequest;
import com.example.endpointadmin.grid.DeviceGridQueryService;
import com.example.endpointadmin.grid.GridErrorResponse;
import com.example.endpointadmin.grid.GridQueryValidationException;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE — endpoint-device grid server-side data source (board #1154 PR-2a).
 *
 * <p>{@code POST /api/v1/admin/endpoint-devices/query} is the AG Grid
 * Server-Side Row Model (SSRM) block endpoint: it returns one page of
 * devices, each already carrying the latest device-health (AG-033) +
 * outdated-software (AG-036) summary columns joined server-side (LATERAL,
 * schema-qualified). Converting the grid to server mode is what unlocks the
 * report-style "Mevcut görünüm" (current-view) export in PR-2b/PR-3 — that
 * export only fires for a server-mode grid.
 *
 * <p>RBAC: {@code module:endpoint-admin} {@code can_view} via
 * {@link RequireModule} — parity with the existing device + snapshot
 * endpoints, no new OpenFGA scope.
 *
 * <p>Deliberately NOT {@code @Transactional}: the query is a single
 * read-only JDBC statement with no lazy associations (see
 * {@link DeviceGridQueryService}).
 *
 * <p>Validation failures ({@link GridQueryValidationException}) map to
 * {@code 400} with a stable {@link GridErrorResponse} code
 * ({@code INVALID_GRID_FILTER} / {@code INVALID_GRID_SORT} /
 * {@code INVALID_ROW_WINDOW}) so the frontend can branch deterministically.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointDeviceGridController {

    private final DeviceGridQueryService gridQueryService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointDeviceGridController(DeviceGridQueryService gridQueryService,
                                             TenantContextResolver tenantContextResolver) {
        this.gridQueryService = gridQueryService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-devices/query")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ResponseEntity<Object> query(@RequestBody(required = false) DeviceGridQueryRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        DeviceGridQueryRequest safe = request != null ? request
                : new DeviceGridQueryRequest(null, null, null, null, null);
        try {
            return ResponseEntity.ok(gridQueryService.query(context.tenantId(), safe));
        } catch (GridQueryValidationException e) {
            return ResponseEntity.badRequest()
                    .body(new GridErrorResponse(e.getCode(), e.getMessage()));
        }
    }
}
