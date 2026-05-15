package com.example.report.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.query.QueryEngine;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * PR6c-1 — ReportExportController authorization tests (previously uncovered).
 *
 * <p>Export endpoint gates on {@code REPORT_VIEW} (via
 * {@link ReportAccessEvaluator#evaluate}) AND {@code REPORT_EXPORT}
 * (via {@link ReportAccessEvaluator#canExport}). Super-admin bypasses both.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportExportControllerAuthzTest {

    @Mock private PermissionResolver permissionResolver;
    @Mock private ReportRegistry registry;
    @Mock private QueryEngine queryEngine;
    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private ReportAuditClient auditClient;

    private ReportExportController controller;

    @BeforeEach
    void setUp() {
        controller = new ReportExportController(
                registry,
                permissionResolver,
                new ReportAccessEvaluator(),
                queryEngine,
                jdbc,
                auditClient,
                new ObjectMapper(),
                new com.example.report.authz.CompanyHeaderScopeNarrower());
    }

    @Test
    void export_nonExistentReport_404() {
        when(permissionResolver.getAuthzMe(any())).thenReturn(authzWith(true, List.of()));
        when(registry.get("ghost")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                controller.exportReport("ghost", "csv", null, null, null, testJwt("admin")));
    }

    @Test
    void export_noReportView_403() {
        // User has no REPORT_VIEW permission at all.
        AuthzMeResponse authz = authzWith(false, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(report("any", null)));

        assertThrows(ResponseStatusException.class, () ->
                controller.exportReport("any", "csv", null, null, null, testJwt("user1")));
    }

    @Test
    void export_reportViewOnlyNoExport_403() {
        // REPORT_VIEW present but REPORT_EXPORT missing → deny on canExport.
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(report("any", null)));

        var ex = assertThrows(ResponseStatusException.class, () ->
                controller.exportReport("any", "csv", null, null, null, testJwt("user1")));
        // Reason preserves the legacy error message so smoke tests can assert on it.
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void export_reportViewAndExport_success() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW", "REPORT_EXPORT"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(report("any", null)));
        when(queryEngine.buildExportQuery(any(), any(), any(), any()))
                .thenReturn(mock(SqlBuilder.BuiltQuery.class));
        when(queryEngine.getVisibleColumns(any(), any())).thenReturn(List.of("col1"));

        var response = controller.exportReport("any", "csv", null, null, null, testJwt("user1"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void export_superAdmin_success() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(report("any", null)));
        when(queryEngine.buildExportQuery(any(), any(), any(), any()))
                .thenReturn(mock(SqlBuilder.BuiltQuery.class));
        when(queryEngine.getVisibleColumns(any(), any())).thenReturn(List.of("col1"));

        var response = controller.exportReport("any", "csv", null, null, null, testJwt("admin"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void export_excelFormat_contentTypeSwitches() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(report("any", null)));
        when(queryEngine.buildExportQuery(any(), any(), any(), any()))
                .thenReturn(mock(SqlBuilder.BuiltQuery.class));
        when(queryEngine.getVisibleColumns(any(), any())).thenReturn(List.of("col1"));

        var response = controller.exportReport("any", "excel", null, null, null, testJwt("admin"));

        assertEquals(200, response.getStatusCode().value());
        var contentType = response.getHeaders().getFirst("Content-Type");
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", contentType);
    }

    // ── PR-0.5b POST /export — dispatch + validation parity with /query ──

    @Test
    void exportPost_pivotMissingPivotValues_400PivotNotConfigured() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGroupingPivot("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "ba", "amount"));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                List.of(new com.example.report.dto.ColumnVO("category", "Category", "category", null)),
                List.of(new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "sum")),
                List.of(new com.example.report.dto.ColumnVO("ba", "B/A", "ba", null)),
                true,
                null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(400, response.getStatusCode().value());
        var err = (com.example.report.dto.ReportQueryErrorDto) response.getBody();
        assertEquals("PIVOT_NOT_CONFIGURED", err.code());
    }

    @Test
    void exportPost_groupedNonAggregatableValue_400InvalidAggregation() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGrouping("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "note"));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                List.of(new com.example.report.dto.ColumnVO("category", "Category", "category", null)),
                // note is not aggregatable in the registry → must 400
                List.of(new com.example.report.dto.ColumnVO("note", "Note", "note", "sum")),
                null, false, null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(400, response.getStatusCode().value());
        var err = (com.example.report.dto.ReportQueryErrorDto) response.getBody();
        assertEquals("INVALID_AGGREGATION_REQUEST", err.code());
    }

    @Test
    void exportPost_duplicateValueCols_400InvalidAggregation() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGrouping("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "amount"));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                List.of(new com.example.report.dto.ColumnVO("category", "Category", "category", null)),
                List.of(
                        new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "sum"),
                        new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "avg")),
                null, false, null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(400, response.getStatusCode().value());
        var err = (com.example.report.dto.ReportQueryErrorDto) response.getBody();
        assertEquals("INVALID_AGGREGATION_REQUEST", err.code());
    }

    @Test
    void exportPost_incompletePivot_400GroupingNotSupported() {
        // pivotMode=true but no rowGroupCols → contradictory snapshot.
        // Live /query path rejects this; export must too.
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGroupingPivot("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "ba", "amount"));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                null,
                List.of(new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "sum")),
                List.of(new com.example.report.dto.ColumnVO("ba", "B/A", "ba", null)),
                true,
                null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(400, response.getStatusCode().value());
        var err = (com.example.report.dto.ReportQueryErrorDto) response.getBody();
        assertEquals("GROUPING_NOT_SUPPORTED", err.code());
    }

    @Test
    void exportPost_nonGroupableColumn_400GroupingNotSupported() {
        // The request marks `amount` as a row-group dim, but registry
        // says it's not groupable → must reject.
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGrouping("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "amount"));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                List.of(new com.example.report.dto.ColumnVO("amount", "Amount", "amount", null)),
                List.of(new com.example.report.dto.ColumnVO("category", "Category", "category", "count")),
                null, false, null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(400, response.getStatusCode().value());
        var err = (com.example.report.dto.ReportQueryErrorDto) response.getBody();
        assertEquals("GROUPING_NOT_SUPPORTED", err.code());
    }

    @Test
    void exportPost_validGroupedRequest_200Csv() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGrouping("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "amount"));
        when(queryEngine.buildGroupedExportQuery(any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(SqlBuilder.BuiltQuery.class));

        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv",
                List.of(new com.example.report.dto.ColumnVO("category", "Category", "category", null)),
                List.of(new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "sum")),
                null, false, null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(200, response.getStatusCode().value());
        var contentType = response.getHeaders().getFirst("Content-Type");
        assertEquals("text/csv; charset=UTF-8", contentType);
    }

    @Test
    void exportPost_flatRequest_dispatchesToFlatExporter() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("any")).thenReturn(Optional.of(reportWithGrouping("any")));
        when(queryEngine.getVisibleColumns(any(), any()))
                .thenReturn(List.of("category", "amount"));
        when(queryEngine.buildExportQuery(any(), any(), any(), any()))
                .thenReturn(mock(SqlBuilder.BuiltQuery.class));

        // No grouping intent — must fall through to flat export.
        var dto = new com.example.report.dto.ReportExportRequestDto(
                "csv", null, null, null, false, null, null);

        var response = controller.exportReportPost("any", dto, null, testJwt("admin"));

        assertEquals(200, response.getStatusCode().value());
    }

    private static ReportDefinition reportWithGrouping(String key) {
        return new ReportDefinition(
                key, "1", "Report " + key, "desc", "category",
                "dbo.fact_table", "dbo", "static", null, null,
                List.of(
                        new ColumnDefinition("category", "Category", "text", 150,
                                false, true, false, null),
                        new ColumnDefinition("amount", "Amount", "number", 120,
                                false, false, true, "sum"),
                        new ColumnDefinition("note", "Note", "text", 150,
                                false, false, false, null)),
                null, null, null);
    }

    private static ReportDefinition reportWithGroupingPivot(String key) {
        return new ReportDefinition(
                key, "1", "Report " + key, "desc", "category",
                "dbo.fact_table", "dbo", "static", null, null,
                List.of(
                        new ColumnDefinition("category", "Category", "text", 150,
                                false, true, false, null),
                        new ColumnDefinition("ba", "B/A", "text", 100,
                                false, true, false, null, null, true, null),
                        new ColumnDefinition("amount", "Amount", "number", 120,
                                false, false, true, "sum")),
                null, null, null);
    }

    // ---- helpers ---------------------------------------------------------

    private static ReportDefinition report(String key, String permission) {
        var access = permission != null
                ? new AccessConfig(permission, null, null, null)
                : null;
        return new ReportDefinition(
                key,
                "1",
                "Report " + key,
                "desc",
                "category",
                "dbo.fact_table",
                "dbo",
                "static",
                null,
                null,
                List.of(new ColumnDefinition("col1", "Col 1", "text", 150, false)),
                null,
                null,
                access);
    }

    private static AuthzMeResponse authzWith(boolean superAdmin, List<String> permissions) {
        var authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(permissions);
        authz.setUserId("test-user");
        return authz;
    }

    private static Jwt testJwt(String username) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("preferred_username", username)
                .claim("sub", username)
                .claim("email", username + "@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
