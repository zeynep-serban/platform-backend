package com.example.report.query;

import com.example.report.access.ColumnFilter;
import com.example.report.access.RowFilterInjector;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryEngineTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ColumnFilter columnFilter;
    @Mock RowFilterInjector rowFilterInjector;
    @Mock YearlySchemaResolver yearlySchemaResolver;

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        // Codex 019e0d06 iter-2 absorb: schemaMode=current dispatch deps —
        // current resolver + ReportRegistry side-channel. Default: empty
        // tenantBoundary so legacy null-fallback path runs.
        com.example.report.query.CurrentTenantSchemaResolver currentResolver =
                org.mockito.Mockito.mock(com.example.report.query.CurrentTenantSchemaResolver.class);
        com.example.report.registry.ReportRegistry registry =
                org.mockito.Mockito.mock(com.example.report.registry.ReportRegistry.class);
        org.mockito.Mockito.when(registry.getTenantBoundary(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        engine = new QueryEngine(jdbc, columnFilter, rowFilterInjector, yearlySchemaResolver,
                currentResolver, registry);
    }

    private static ReportDefinition staticDef() {
        return new ReportDefinition(
                "test-report", "v1", "Test", "desc", "cat",
                "EMPLOYEES", "dbo", "static", null, null,
                List.of(new ColumnDefinition("id", "ID", "number", 100, false),
                        new ColumnDefinition("name", "Name", "text", 200, false)),
                "id", "ASC", new AccessConfig(null, null, null, null));
    }

    private static ReportDefinition yearlyDef() {
        return new ReportDefinition(
                "yearly-report", "v1", "Yearly", "desc", "cat",
                "TRANSACTIONS", "dbo", "yearly", "TxDate", null,
                List.of(new ColumnDefinition("TxDate", "Date", "date", 150, false)),
                "TxDate", "DESC", null);
    }

    private static AuthzMeResponse authz() {
        var a = new AuthzMeResponse();
        a.setSuperAdmin(true);
        return a;
    }

    private void mockDefaults() {
        when(columnFilter.getVisibleColumns(any(), any())).thenReturn(List.of("id", "name"));
        when(rowFilterInjector.buildRlsClause(any(), any()))
                .thenReturn(new RowFilterInjector.RlsResult(null, null));
    }

    @Nested
    @DisplayName("executeQuery")
    class ExecuteQuery {

        @Test
        @DisplayName("returns paged data with items and count")
        void pagedData() {
            mockDefaults();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of(Map.of("id", 1, "name", "Alice")));
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(42L);

            var result = engine.executeQuery(staticDef(), authz(), Map.of(), List.of(), 1, 20);

            assertEquals(1, result.items().size());
            assertEquals(42L, result.total());
            assertEquals(1, result.page());
            assertEquals(20, result.pageSize());
        }

        @Test
        @DisplayName("count query failure returns -1")
        void countFailure() {
            mockDefaults();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of());
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenThrow(new RuntimeException("count failed"));

            var result = engine.executeQuery(staticDef(), authz(), Map.of(), List.of(), 1, 10);

            assertEquals(-1, result.total());
        }

        @Test
        @DisplayName("yearly report triggers schema resolution")
        void yearlySchemaResolution() {
            when(columnFilter.getVisibleColumns(any(), any())).thenReturn(List.of("TxDate"));
            when(rowFilterInjector.buildRlsClause(any(), any()))
                    .thenReturn(new RowFilterInjector.RlsResult(null, null));
            when(yearlySchemaResolver.resolve(any(), any(), any()))
                    .thenReturn(new YearlySchemaResolver.ResolvedSchemas(List.of(
                            new YearlySchemaResolver.Branch(
                                    "db_2024", 2024, 0L, "workcube_mikrolink", true))));
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of());
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(0L);

            engine.executeQuery(yearlyDef(), authz(), Map.of(), List.of(), 1, 10);

            verify(yearlySchemaResolver).resolve(any(), any(), any());
        }

        @Test
        @DisplayName("static report does NOT trigger schema resolution")
        void staticNoSchemaResolution() {
            mockDefaults();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of());
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(0L);

            engine.executeQuery(staticDef(), authz(), Map.of(), List.of(), 1, 10);

            verifyNoInteractions(yearlySchemaResolver);
        }
    }

    @Nested
    @DisplayName("buildExportQuery")
    class BuildExport {

        @Test
        @DisplayName("builds export query with visible columns and RLS")
        void exportQuery() {
            mockDefaults();

            var result = engine.buildExportQuery(staticDef(), authz(), Map.of(), List.of());

            assertNotNull(result);
            assertTrue(result.sql().contains("SELECT TOP"));
        }
    }

    @Nested
    @DisplayName("getVisibleColumns")
    class VisibleCols {

        @Test
        @DisplayName("delegates to columnFilter")
        void delegatesToColumnFilter() {
            when(columnFilter.getVisibleColumns(any(), any())).thenReturn(List.of("id", "name"));

            var result = engine.getVisibleColumns(staticDef(), authz());

            assertEquals(List.of("id", "name"), result);
            verify(columnFilter).getVisibleColumns(any(), any());
        }
    }
}
