package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.TableInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * P1 (handoff section 5 - Codex 019e32da / 019e3317): {@code extractTables}
 * split tests. {@code extractTables} is now {@code extractBaseTables} (the
 * mandatory base catalog read) plus {@code enrichTables} (three independent
 * non-fatal {@code sys.*} enrichment queries). The mocked {@code jdbc.query}
 * is invoked four times - base + identity + default + computed - so the stub
 * branches on the SQL string. Pins: full enrichment merge, per-surface
 * non-fatal isolation, total-degradation = base columns, unknown-column
 * no-op, and the base SQL no longer carrying the enrichment JOINs.
 */
class SchemaExtractServiceTableExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private List<ResultSet> baseRows = List.of();
    private List<ResultSet> identityRows = List.of();
    private List<ResultSet> defaultRows = List.of();
    private List<ResultSet> computedRows = List.of();
    private boolean identityThrows;
    private boolean defaultThrows;
    private boolean computedThrows;
    private String capturedBaseSql;

    /** jdbc.query is called 4x (base + 3 enrichment); branch on the SQL text. */
    private void installStub() {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            List<ResultSet> rows;
            if (sql.contains("sys.identity_columns")) {
                if (identityThrows) {
                    throw new RuntimeException("identity query failed");
                }
                rows = identityRows;
            } else if (sql.contains("sys.default_constraints")) {
                if (defaultThrows) {
                    throw new RuntimeException("default query failed");
                }
                rows = defaultRows;
            } else if (sql.contains("sys.computed_columns")) {
                if (computedThrows) {
                    throw new RuntimeException("computed query failed");
                }
                rows = computedRows;
            } else {
                capturedBaseSql = sql;
                rows = baseRows;
            }
            for (ResultSet r : rows) {
                handler.processRow(r);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    private static ResultSet baseCol(String table, String col, String dataType,
            boolean nullable, boolean identity, boolean pk, boolean sparse, int ordinal)
            throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("table_name")).thenReturn(table);
        when(r.getString("column_name")).thenReturn(col);
        when(r.getString("data_type")).thenReturn(dataType);
        when(r.getInt("max_length")).thenReturn(0);
        when(r.getInt("precision")).thenReturn(0);
        when(r.getInt("scale")).thenReturn(0);
        when(r.getString("collation_name")).thenReturn(null);
        when(r.getBoolean("is_nullable")).thenReturn(nullable);
        when(r.getBoolean("is_identity")).thenReturn(identity);
        when(r.getBoolean("is_pk")).thenReturn(pk);
        when(r.getBoolean("is_sparse")).thenReturn(sparse);
        when(r.getInt("ordinal")).thenReturn(ordinal);
        return r;
    }

    private static ResultSet identityRow(String table, String col, long seed, long increment)
            throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("table_name")).thenReturn(table);
        when(r.getString("column_name")).thenReturn(col);
        when(r.getLong("identity_seed")).thenReturn(seed);
        when(r.getLong("identity_increment")).thenReturn(increment);
        when(r.wasNull()).thenReturn(false);
        return r;
    }

    private static ResultSet defaultRow(String table, String col, String definition)
            throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("table_name")).thenReturn(table);
        when(r.getString("column_name")).thenReturn(col);
        when(r.getString("default_definition")).thenReturn(definition);
        return r;
    }

    private static ResultSet computedRow(String table, String col, String definition,
            boolean persisted) throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("table_name")).thenReturn(table);
        when(r.getString("column_name")).thenReturn(col);
        when(r.getString("computed_definition")).thenReturn(definition);
        when(r.getBoolean("computed_persisted")).thenReturn(persisted);
        return r;
    }

    @Test
    void extractTables_mergesIdentityDefaultComputed() throws SQLException {
        baseRows = List.of(
            baseCol("INVOICE", "ID", "int", false, true, true, false, 1),
            baseCol("INVOICE", "TOTAL", "decimal", true, false, false, false, 2));
        identityRows = List.of(identityRow("INVOICE", "ID", 1L, 1L));
        defaultRows = List.of(defaultRow("INVOICE", "TOTAL", "((0))"));
        computedRows = List.of(computedRow("INVOICE", "TOTAL", "([A]+[B])", true));
        installStub();

        Map<String, TableInfo> tables = service.extractTables("workcube_mikrolink");

        List<ColumnInfo> cols = tables.get("INVOICE").columns();
        ColumnInfo id = cols.get(0);
        assertThat(id.name()).isEqualTo("ID");
        assertThat(id.identity()).isTrue();
        assertThat(id.identitySeed()).isEqualTo(1L);
        assertThat(id.identityIncrement()).isEqualTo(1L);
        assertThat(id.pk()).isTrue();
        assertThat(id.ordinal()).isEqualTo(1);
        assertThat(id.identitySeed()).isNotNull();
        ColumnInfo total = cols.get(1);
        assertThat(total.defaultExpression()).isEqualTo("((0))");
        assertThat(total.computedExpression()).isEqualTo("([A]+[B])");
        assertThat(total.computedPersisted()).isTrue();
        assertThat(total.identitySeed()).isNull();   // no identity row for TOTAL
    }

    @Test
    void identityEnrichmentThrows_defaultAndComputedStillApply() throws SQLException {
        baseRows = List.of(
            baseCol("ORDERS", "STATUS", "int", false, false, false, false, 1),
            baseCol("ORDERS", "FULL_NAME", "nvarchar", true, false, false, false, 2));
        identityThrows = true;                       // identity surface fails
        defaultRows = List.of(defaultRow("ORDERS", "STATUS", "((1))"));
        computedRows = List.of(computedRow("ORDERS", "FULL_NAME", "([A]+[B])", true));
        installStub();

        Map<String, TableInfo> tables = service.extractTables("workcube_mikrolink");

        List<ColumnInfo> cols = tables.get("ORDERS").columns();
        // identity enrichment failed -> seed/increment null, base intact
        assertThat(cols.get(0).identitySeed()).isNull();
        assertThat(cols.get(0).name()).isEqualTo("STATUS");
        // default + computed independently still applied
        assertThat(cols.get(0).defaultExpression()).isEqualTo("((1))");
        assertThat(cols.get(1).computedExpression()).isEqualTo("([A]+[B])");
        assertThat(cols.get(1).computedPersisted()).isTrue();
    }

    @Test
    void allEnrichmentThrows_baseColumnsReturnedUnchanged() throws SQLException {
        baseRows = List.of(baseCol("COMPANY", "CODE", "nvarchar", false, false, true, false, 1));
        identityThrows = true;
        defaultThrows = true;
        computedThrows = true;
        installStub();

        Map<String, TableInfo> tables = service.extractTables("workcube_mikrolink");

        ColumnInfo code = tables.get("COMPANY").columns().get(0);
        assertThat(code.name()).isEqualTo("CODE");
        assertThat(code.pk()).isTrue();                 // base field intact
        assertThat(code.identitySeed()).isNull();       // enrichment degraded, not fatal
        assertThat(code.identityIncrement()).isNull();
        assertThat(code.defaultExpression()).isNull();
        assertThat(code.computedExpression()).isNull();
        assertThat(code.computedPersisted()).isFalse();
    }

    @Test
    void enrichmentRowForUnknownColumn_isNoOp() throws SQLException {
        baseRows = List.of(baseCol("EMPLOYEE", "ID", "int", false, true, true, false, 1));
        // enrichment references a column absent from the base map
        identityRows = List.of(identityRow("EMPLOYEE", "GHOST_COL", 5L, 5L));
        installStub();

        Map<String, TableInfo> tables = service.extractTables("workcube_mikrolink");

        List<ColumnInfo> cols = tables.get("EMPLOYEE").columns();
        assertThat(cols).hasSize(1);                    // ghost column not introduced
        assertThat(cols.get(0).name()).isEqualTo("ID");
        assertThat(cols.get(0).identitySeed()).isNull();// ID had no enrichment row of its own
    }

    @Test
    void extractBaseTables_sqlOmitsEnrichmentJoins() throws SQLException {
        baseRows = List.of(baseCol("T", "C", "int", false, false, false, false, 1));
        installStub();

        service.extractBaseTables("workcube_mikrolink");

        assertThat(capturedBaseSql)
            .doesNotContain("sys.identity_columns")
            .doesNotContain("sys.default_constraints")
            .doesNotContain("sys.computed_columns")
            .contains("is_primary_key = 1")   // PK detection stays in the base read
            .contains("sys.columns");
    }
}
