package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.access.ReportAccessEvaluator.AccessResult;
import com.example.report.access.RowFilterInjector;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.authz.ScopeSummaryDto;
import com.example.report.dto.PagedResultDto;
import com.example.report.query.CurrentTenantSchemaResolver;
import com.example.report.query.YearlySchemaResolver;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 2 Program 11.3 — execution service unit tests (Codex iter-29
 * acceptance).
 *
 * <p>Adapter call wiring verified; full authz chain (ColumnFilter +
 * RowFilter + ReportAccessEvaluator + schema resolver) lands in Adım
 * 11.4 with interim gate removal.
 */
class WorkcubeReportExecutionServiceTest {

    private ReportRegistry registry;
    private PermissionResolver permissionResolver;
    private CompanyHeaderScopeNarrower narrower;
    private WorkcubeQueryAdapter adapter;
    private WorkcubeReportExecutionService service;

    private ReportAccessEvaluator accessEvaluator;
    private ColumnFilter columnFilter;
    private RowFilterInjector rowFilterInjector;
    private ReportAuditClient auditClient;
    private YearlySchemaResolver yearlySchemaResolver;
    private CurrentTenantSchemaResolver currentTenantSchemaResolver;

    @BeforeEach
    void setUp() {
        registry = mock(ReportRegistry.class);
        permissionResolver = mock(PermissionResolver.class);
        narrower = new CompanyHeaderScopeNarrower();
        adapter = mock(WorkcubeQueryAdapter.class);
        accessEvaluator = mock(ReportAccessEvaluator.class);
        columnFilter = mock(ColumnFilter.class);
        rowFilterInjector = mock(RowFilterInjector.class);
        auditClient = mock(ReportAuditClient.class);
        yearlySchemaResolver = mock(YearlySchemaResolver.class);
        currentTenantSchemaResolver = mock(CurrentTenantSchemaResolver.class);
        // Sensible defaults: allow access, expose all columns, no RLS clause
        when(accessEvaluator.evaluate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(AccessResult.ALLOWED);
        when(columnFilter.getVisibleColumns(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("INVOICE_ID", "FULLNAME"));
        when(rowFilterInjector.buildRlsClause(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RowFilterInjector.RlsResult(null, null));

        service = new WorkcubeReportExecutionService(
                registry, permissionResolver, narrower, adapter,
                yearlySchemaResolver, currentTenantSchemaResolver,
                accessEvaluator, columnFilter, rowFilterInjector, auditClient,
                new ObjectMapper());
    }

    private ReportDefinition def(String key) {
        return new ReportDefinition(
                key, "1.0", "Title", "Description", "category",
                "INVOICE", "dbo", "static", null, null,
                List.of(
                        new ColumnDefinition("INVOICE_ID", "ID", "number", 50, false),
                        new ColumnDefinition("FULLNAME", "Name", "text", 200, false)),
                "INVOICE_ID", "ASC", null);
    }

    private AuthzMeResponse superAdmin() {
        AuthzMeResponse a = new AuthzMeResponse();
        a.setUserId("admin");
        a.setSuperAdmin(true);
        a.setAllowedScopes(List.of());
        return a;
    }

    private AuthzMeResponse scopedUser(String... companyIds) {
        AuthzMeResponse a = new AuthzMeResponse();
        a.setUserId("user");
        a.setSuperAdmin(false);
        a.setAllowedScopes(java.util.Arrays.stream(companyIds)
                .map(id -> new ScopeSummaryDto("COMPANY", id))
                .toList());
        return a;
    }

    @Test
    void executeData_unknownReportKey_throws404() {
        when(registry.get("rogue")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeData(
                "rogue", 1, 50, null, null, null, mock(Jwt.class)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(t -> ((ResponseStatusException) t).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(adapter, never()).executeData(any(), any(), anyList(), anyMap(),
                anyList(), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void executeData_validReport_returnsPagedResult() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(Map.of("INVOICE_ID", 1, "FULLNAME", "Alpha")));

        PagedResultDto<Map<String, Object>> result = service.executeData(
                "workcube-inv", 1, 50, null, null, null, mock(Jwt.class));

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(50);
    }

    @Test
    void executeData_pageSize_clampedToBounds() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        // pageSize=9999 → clamped to 500
        PagedResultDto<Map<String, Object>> result = service.executeData(
                "workcube-inv", 1, 9999, null, null, null, mock(Jwt.class));
        assertThat(result.pageSize()).isEqualTo(500);

        // pageSize=0 → defaults to 50
        result = service.executeData("workcube-inv", 1, 0, null, null, null, mock(Jwt.class));
        assertThat(result.pageSize()).isEqualTo(50);
    }

    @Test
    void executeData_companyHeaderNarrowsAuthz() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any()))
                .thenReturn(scopedUser("35", "99"));
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        // X-Company-Id=35 → narrower keeps singleton COMPANY 35
        service.executeData("workcube-inv", 1, 50, null, null, "35", mock(Jwt.class));

        verify(adapter).executeData(any(), any(), anyList(), anyMap(),
                anyList(), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void executeData_visibleColumnsDerivedFromDef() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.executeData("workcube-inv", 1, 50, null, null, null, mock(Jwt.class));

        // ArgumentCaptor would be cleaner but unnecessary; verify the
        // adapter was invoked exactly once with the same def
        verify(adapter).executeData(any(), any(),
                org.mockito.ArgumentMatchers.argThat(cols ->
                        cols.contains("INVOICE_ID") && cols.contains("FULLNAME")),
                anyMap(), anyList(), anyString(),
                any(MapSqlParameterSource.class), anyInt(), anyInt());
    }

    @Test
    void executeCount_unknownReportKey_throws404() {
        when(registry.get("rogue")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.executeCount(
                "rogue", null, null, mock(Jwt.class)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void executeCount_validReport_delegatesToAdapter() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(adapter.executeCount(any(), any(), anyMap(), anyList(),
                anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(42L);

        long count = service.executeCount("workcube-inv", null, null, mock(Jwt.class));
        assertThat(count).isEqualTo(42L);
    }

    // ---- Adım 11.4: full authz pipeline acceptance ------------------------

    @Test
    void executeData_accessDenied_throwsForbidden_andAuditDenied() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(scopedUser("35"));
        when(accessEvaluator.evaluate(any(), any()))
                .thenReturn(AccessResult.DENIED_NO_REPORT_PERMISSION);

        assertThatThrownBy(() -> service.executeData(
                "workcube-inv", 1, 50, null, null, null, mock(Jwt.class)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(t -> ((org.springframework.web.server.ResponseStatusException) t).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);

        org.mockito.Mockito.verify(auditClient).logReportAccessDenied(
                org.mockito.ArgumentMatchers.eq("workcube-inv"),
                any(), any(),
                org.mockito.ArgumentMatchers.contains("DENIED"));
        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never())
                .executeData(any(), any(), anyList(), anyMap(), anyList(),
                        anyString(), any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void executeData_accessAllowed_auditSuccessInvoked() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(adapter.executeCount(any(), any(), anyMap(), anyList(),
                anyString(), any(MapSqlParameterSource.class))).thenReturn(0L);

        service.executeData("workcube-inv", 1, 50, null, null, null, mock(Jwt.class));

        org.mockito.Mockito.verify(auditClient).logReportAccess(
                org.mockito.ArgumentMatchers.eq("workcube-inv"), any(), any());
        org.mockito.Mockito.verify(auditClient, org.mockito.Mockito.never())
                .logReportAccessDenied(any(), any(), any(), any());
    }

    @Test
    void executeData_columnFilterDictatesVisibleColumns() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(columnFilter.getVisibleColumns(any(), any()))
                .thenReturn(List.of("INVOICE_ID"));  // FULLNAME hidden via column-level RLS
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(adapter.executeCount(any(), any(), anyMap(), anyList(),
                anyString(), any(MapSqlParameterSource.class))).thenReturn(0L);

        service.executeData("workcube-inv", 1, 50, null, null, null, mock(Jwt.class));

        // verify adapter received exactly the ColumnFilter-supplied visibleColumns
        org.mockito.Mockito.verify(adapter).executeData(any(), any(),
                org.mockito.ArgumentMatchers.argThat(cols ->
                        cols.size() == 1 && cols.contains("INVOICE_ID")),
                anyMap(), anyList(), anyString(),
                any(MapSqlParameterSource.class), anyInt(), anyInt());
    }

    @Test
    void executeData_rowFilterAppendsRlsClause() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(scopedUser("35"));
        when(rowFilterInjector.buildRlsClause(any(), any()))
                .thenReturn(new RowFilterInjector.RlsResult(
                        "[COMPANY_ID] IN (:_rlsIds)",
                        new MapSqlParameterSource("_rlsIds", List.of(35L))));
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(adapter.executeCount(any(), any(), anyMap(), anyList(),
                anyString(), any(MapSqlParameterSource.class))).thenReturn(0L);

        service.executeData("workcube-inv", 1, 50, null, null, "35", mock(Jwt.class));

        // adapter receives non-empty RLS clause
        org.mockito.Mockito.verify(adapter).executeData(any(), any(), anyList(),
                anyMap(), anyList(),
                org.mockito.ArgumentMatchers.argThat(rls ->
                        rls != null && rls.contains("COMPANY_ID") && rls.contains(":_rlsIds")),
                any(MapSqlParameterSource.class), anyInt(), anyInt());
    }

    // ---- Adım 11.4 REVISE-1 Codex iter-34: Workcube pipeline composite ----

    @Test
    void executeData_scopedUserValidPermissionWithHeader_returns200_AdimaSemantic() {
        // Codex iter-34 Blocker 1: prove interim gate actually removed —
        // non-super-admin with valid permission + valid X-Company-Id can
        // now execute Workcube reports (was 403 before Adım 11.4).
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(scopedUser("35"));
        when(adapter.executeData(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(List.of(Map.of("x", 1)));
        when(adapter.executeCount(any(), any(), anyMap(), anyList(),
                anyString(), any(MapSqlParameterSource.class))).thenReturn(1L);

        PagedResultDto<Map<String, Object>> result = service.executeData(
                "workcube-inv", 1, 50, null, null, "35", mock(Jwt.class));

        assertThat(result.items()).hasSize(1);
        org.mockito.Mockito.verify(auditClient).logReportAccess(
                org.mockito.ArgumentMatchers.eq("workcube-inv"), any(), any());
    }

    @Test
    void executeData_multiCompanyNoHeader_yearly_throwsTenantSelectionRequired() {
        // Codex iter-35 Blocker 2: multi-company scoped user, yearly report,
        // no X-Company-Id header → YearlySchemaResolver throws
        // TenantSelectionRequiredException via Workcube pipeline.
        ReportDefinition yearlyDef = new ReportDefinition(
                "workcube-yearly", "1.0", "Yearly", "test", "test",
                "INVOICE", null, "yearly", "year", null,
                List.of(new ColumnDefinition("INVOICE_ID", "ID", "number", 50, false)),
                "INVOICE_ID", "ASC", null);
        when(registry.get("workcube-yearly")).thenReturn(Optional.of(yearlyDef));
        when(permissionResolver.getAuthzMe(any()))
                .thenReturn(scopedUser("35", "99"));
        // schemaResolver throws TenantSelectionRequiredException for multi-company no header
        when(yearlySchemaResolver.resolve(any(), any(), anyMap()))
                .thenThrow(new com.example.report.query.TenantSelectionRequiredException(
                        "workcube-yearly",
                        "Multi-company user must provide X-Company-Id"));

        assertThatThrownBy(() -> service.executeData(
                "workcube-yearly", 1, 50, null, null, null, mock(Jwt.class)))
                .isInstanceOf(com.example.report.query.TenantSelectionRequiredException.class);

        // Verify adapter NOT called (fail before SQL)
        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never())
                .executeData(any(), any(), anyList(), anyMap(), anyList(),
                        anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void executeData_superAdminNoHeader_yearly_throwsTenantSelectionRequired() {
        // Codex iter-35 Blocker 2 part 2: super-admin no scope/no header
        // on yearly report → resolver demands X-Company-Id picker.
        ReportDefinition yearlyDef = new ReportDefinition(
                "workcube-yearly", "1.0", "Yearly", "test", "test",
                "INVOICE", null, "yearly", "year", null,
                List.of(new ColumnDefinition("INVOICE_ID", "ID", "number", 50, false)),
                "INVOICE_ID", "ASC", null);
        when(registry.get("workcube-yearly")).thenReturn(Optional.of(yearlyDef));
        when(permissionResolver.getAuthzMe(any())).thenReturn(superAdmin());
        when(yearlySchemaResolver.resolve(any(), any(), anyMap()))
                .thenThrow(new com.example.report.query.TenantSelectionRequiredException(
                        "workcube-yearly",
                        "Super-admin must use X-Company-Id picker for yearly reports"));

        assertThatThrownBy(() -> service.executeData(
                "workcube-yearly", 1, 50, null, null, null, mock(Jwt.class)))
                .isInstanceOf(com.example.report.query.TenantSelectionRequiredException.class);
    }

    @Test
    void executeData_scopedUserOutOfScopeCompany_throwsResponseStatus403() {
        // Codex iter-34 Blocker 2: out-of-scope company narrow throws
        // ResponseStatusException 403 from CompanyHeaderScopeNarrower in
        // Workcube service pipeline (not just narrower unit test).
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(scopedUser("35"));

        assertThatThrownBy(() -> service.executeData(
                "workcube-inv", 1, 50, null, null, "99", mock(Jwt.class)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting(t -> ((org.springframework.web.server.ResponseStatusException) t).getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test
    void executeCount_accessDenied_throwsForbidden() {
        ReportDefinition def = def("workcube-inv");
        when(registry.get("workcube-inv")).thenReturn(Optional.of(def));
        when(permissionResolver.getAuthzMe(any())).thenReturn(scopedUser("35"));
        when(accessEvaluator.evaluate(any(), any()))
                .thenReturn(AccessResult.DENIED_NO_REPORT_PERMISSION);

        assertThatThrownBy(() -> service.executeCount(
                "workcube-inv", null, null, mock(Jwt.class)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
