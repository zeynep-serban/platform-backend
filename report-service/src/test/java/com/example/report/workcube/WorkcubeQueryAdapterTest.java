package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.query.SqlBuilder;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase 2 Program 11.2b — adapter unit tests (Adım 11.2b).
 *
 * <p>Codex iter-22 acceptance: rendered SQL enforcement, fail-closed on
 * UNKNOWN / UNQUALIFIED, non-V1 deny, allowlisted execute pass-through.
 * Testcontainers-backed integration tests are deferred to 11.2b-2.
 */
class WorkcubeQueryAdapterTest {

    private SqlBuilder sqlBuilder;
    private NamedParameterJdbcTemplate jdbc;
    private WorkcubeQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        sqlBuilder = mock(SqlBuilder.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        adapter = new WorkcubeQueryAdapter(sqlBuilder, jdbc, new CompositeTenantBoundaryEnforcer());
    }

    private ReportDefinition def(String key) {
        return new ReportDefinition(
                key, "1.0", "Title", "Description", "category",
                "INVOICE", "dbo", "static", null, null,
                List.of(new ColumnDefinition("col", "col", "STRING", null, false, false, false, null)),
                null, "ASC", null
        );
    }

    // ---- enforceRendered (direct) -----------------------------------------

    @Test
    void enforceRendered_allowsV1Tables() {
        adapter.enforceRendered(def("inv"),
                "SELECT * FROM [{schema}].[INVOICE] WITH (NOLOCK)");
    }

    @Test
    void enforceRendered_allowsCanonicalSchemaRefs() {
        adapter.enforceRendered(def("inv"),
                "SELECT * FROM [workcube_mikrolink].[COMPANY]");
    }

    @Test
    void enforceRendered_failsOnNonAllowlistedTable() {
        assertThatThrownBy(() -> adapter.enforceRendered(def("rogue"),
                "SELECT * FROM [{schema}].[SECRET_TABLE_NOT_IN_V1]"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("SECRET_TABLE_NOT_IN_V1")
                .hasMessageContaining("ReportingAllowlist.V1");
    }

    @Test
    void enforceRendered_failsOnUnqualifiedTarget() {
        assertThatThrownBy(() -> adapter.enforceRendered(def("unq"),
                "SELECT * FROM ACCOUNT_CARD_ROWS"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("unsupported or unqualified");
    }

    @Test
    void enforceRendered_failsOnTempTableTarget() {
        assertThatThrownBy(() -> adapter.enforceRendered(def("temp"),
                "SELECT * FROM #temp_data"))
                .isInstanceOf(WorkcubeQuerySecurityException.class);
    }

    @Test
    void enforceRendered_failsOnOpenQueryTarget() {
        assertThatThrownBy(() -> adapter.enforceRendered(def("oq"),
                "SELECT * FROM OPENQUERY(LINKED, 'SELECT * FROM remote')"))
                .isInstanceOf(WorkcubeQuerySecurityException.class);
    }

    @Test
    void enforceRendered_emptySql_failsClosed() {
        // Codex iter-23 REVISE-1: blank rendered SQL is not safe — it
        // could be a SqlBuilder bug producing an empty payload.
        assertThatThrownBy(() -> adapter.enforceRendered(def("empty"), ""))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void enforceRendered_nullSql_failsClosed() {
        assertThatThrownBy(() -> adapter.enforceRendered(def("null"), null))
                .isInstanceOf(WorkcubeQuerySecurityException.class);
    }

    @Test
    void enforceRendered_sqlWithNoTableRef_failsClosed() {
        // SELECT 1 produces no scanner table refs — must fail-closed
        // (parser miss or non-Workcube payload).
        assertThatThrownBy(() -> adapter.enforceRendered(def("noref"), "SELECT 1 AS x"))
                .isInstanceOf(WorkcubeQuerySecurityException.class)
                .hasMessageContaining("no detectable");
    }

    // ---- executeData (full pipeline) --------------------------------------

    @Test
    void executeData_allowedRenderedSql_returnsRows() {
        SqlBuilder.BuiltQuery built = new SqlBuilder.BuiltQuery(
                "SELECT * FROM [{schema}].[INVOICE]",
                new MapSqlParameterSource());
        when(sqlBuilder.buildDataQuery(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(built);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("col", "v")));

        List<Map<String, Object>> rows = adapter.executeData(def("inv"), null,
                List.of("col"), Map.of(), List.of(), "", new MapSqlParameterSource(),
                1, 50);

        assertThat(rows).hasSize(1);
        verify(jdbc, times(1)).queryForList(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void executeData_securityViolationFromRenderedSql_doesNotExecute() {
        SqlBuilder.BuiltQuery rogue = new SqlBuilder.BuiltQuery(
                "SELECT * FROM [{schema}].[SECRET_TABLE_NOT_IN_V1]",
                new MapSqlParameterSource());
        when(sqlBuilder.buildDataQuery(any(), any(), anyList(), anyMap(), anyList(),
                anyString(), any(), anyInt(), anyInt())).thenReturn(rogue);

        assertThatThrownBy(() -> adapter.executeData(def("rogue"), null,
                List.of("col"), Map.of(), List.of(), "", new MapSqlParameterSource(),
                1, 50))
                .isInstanceOf(WorkcubeQuerySecurityException.class);

        // jdbc.queryForList must NOT have been called
        verify(jdbc, times(0)).queryForList(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void executeCount_allowedRenderedSql_returnsCount() {
        SqlBuilder.BuiltQuery built = new SqlBuilder.BuiltQuery(
                "SELECT COUNT_BIG(*) FROM [{schema}].[INVOICE]",
                new MapSqlParameterSource());
        when(sqlBuilder.buildCountQuery(any(), any(), anyMap(), anyList(),
                anyString(), any())).thenReturn(built);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(42L);

        long count = adapter.executeCount(def("inv"), null,
                Map.of(), List.of("col"), "", new MapSqlParameterSource());

        assertThat(count).isEqualTo(42L);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
