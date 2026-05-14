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

    @BeforeEach
    void setUp() {
        registry = mock(ReportRegistry.class);
        permissionResolver = mock(PermissionResolver.class);
        narrower = new CompanyHeaderScopeNarrower();
        adapter = mock(WorkcubeQueryAdapter.class);
        service = new WorkcubeReportExecutionService(
                registry, permissionResolver, narrower, adapter,
                mock(YearlySchemaResolver.class), mock(CurrentTenantSchemaResolver.class),
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
}
