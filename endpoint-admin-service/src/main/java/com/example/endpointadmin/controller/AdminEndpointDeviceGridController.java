package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.grid.DeviceGridExportRequest;
import com.example.endpointadmin.grid.DeviceGridExportService;
import com.example.endpointadmin.grid.DeviceGridExportService.ExportPlan;
import com.example.endpointadmin.grid.DeviceGridQueryRequest;
import com.example.endpointadmin.grid.DeviceGridQueryService;
import com.example.endpointadmin.grid.ExportRowLimitExceededException;
import com.example.endpointadmin.grid.GridErrorResponse;
import com.example.endpointadmin.grid.GridQueryValidationException;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    private final DeviceGridExportService exportService;
    private final TenantContextResolver tenantContextResolver;
    private final ObjectMapper objectMapper;

    public AdminEndpointDeviceGridController(DeviceGridQueryService gridQueryService,
                                             DeviceGridExportService exportService,
                                             TenantContextResolver tenantContextResolver,
                                             ObjectMapper objectMapper) {
        this.gridQueryService = gridQueryService;
        this.exportService = exportService;
        this.tenantContextResolver = tenantContextResolver;
        this.objectMapper = objectMapper;
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

    /**
     * {@code POST /api/v1/admin/endpoint-devices/export} — report-style
     * server export (İndir ▾: Ham veri/raw + Mevcut görünüm/view × Excel/CSV).
     * Cap preflight + audit run before the stream (see
     * {@link DeviceGridExportService}); an over-cap dataset is refused with
     * {@code 422 EXPORT_ROW_LIMIT_EXCEEDED}, a bad format/mode/column with
     * {@code 400}. NOT {@code @Transactional} — the body streams outside any
     * session.
     *
     * <p>The method returns {@code ResponseEntity<StreamingResponseBody>} on
     * both paths (error JSON is wrapped in a {@code StreamingResponseBody}):
     * a single return type avoids the generic-erasure converter-resolution
     * failure that a mixed {@code <Object>} body triggers for the binary
     * happy-path content type.
     */
    @PostMapping("/endpoint-devices/export")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ResponseEntity<StreamingResponseBody> export(
            @RequestBody(required = false) DeviceGridExportRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        DeviceGridExportRequest safe = request != null ? request
                : new DeviceGridExportRequest(null, null, null, null, null, null);

        ExportPlan plan;
        try {
            plan = exportService.prepareExport(context.tenantId(), context.subject(), safe);
        } catch (ExportRowLimitExceededException e) {
            return errorBody(HttpStatus.UNPROCESSABLE_ENTITY,
                    new GridErrorResponse(ExportRowLimitExceededException.CODE, e.getMessage(), e.getLimit()));
        } catch (GridQueryValidationException e) {
            return errorBody(HttpStatus.BAD_REQUEST,
                    new GridErrorResponse(e.getCode(), e.getMessage()));
        }

        StreamingResponseBody body = out -> exportService.writeTo(plan, out);
        return ResponseEntity.ok()
                .header("Content-Type", plan.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + plan.filename() + "\"")
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> errorBody(HttpStatus status, GridErrorResponse err) {
        StreamingResponseBody body = out -> out.write(objectMapper.writeValueAsBytes(err));
        return ResponseEntity.status(status)
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(body);
    }
}
