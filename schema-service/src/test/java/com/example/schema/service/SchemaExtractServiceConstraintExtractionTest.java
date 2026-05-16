package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.UniqueConstraintInfo;
import com.example.schema.model.UniqueConstraintType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-2 (capability R1+R2 — Codex 019e2d7d): {@code extractForeignKeys}
 * and {@code extractUniqueConstraints} accumulator-grouping tests. The
 * mocked {@code jdbc.query} replays hand-rolled rows through the
 * {@code RowCallbackHandler}; this locks the multi-row → single-record
 * grouping (composite keys, declared column order) and the field mapping.
 * Actual {@code sys.*} SQL correctness is the integration suite's job.
 */
class SchemaExtractServiceConstraintExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private void stubQuery(List<ResultSet> rows) {
        // jdbc.query(sql, params, RowCallbackHandler) is void → doAnswer.
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            for (ResultSet row : rows) {
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    // --- foreign keys ---

    private static ResultSet fkRow(String fkName, String fromColumn, String toColumn,
                                   boolean disabled, boolean notTrusted) throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("fk_name")).thenReturn(fkName);
        when(r.getString("from_schema")).thenReturn("dbo");
        when(r.getString("from_table")).thenReturn("CHILD");
        when(r.getString("from_column")).thenReturn(fromColumn);
        when(r.getString("to_schema")).thenReturn("dbo");
        when(r.getString("to_table")).thenReturn("PARENT");
        when(r.getString("to_column")).thenReturn(toColumn);
        when(r.getBoolean("is_disabled")).thenReturn(disabled);
        when(r.getBoolean("is_not_trusted")).thenReturn(notTrusted);
        when(r.getString("delete_action")).thenReturn("NO_ACTION");
        when(r.getString("update_action")).thenReturn("CASCADE");
        return r;
    }

    @Test
    void singleColumnForeignKey_yieldsOneInfo() throws SQLException {
        stubQuery(List.of(fkRow("FK_SINGLE", "COMPANY_ID", "ID", false, false)));

        List<ForeignKeyInfo> fks = service.extractForeignKeys("workcube_mikrolink");

        assertThat(fks).hasSize(1);
        ForeignKeyInfo fk = fks.get(0);
        assertThat(fk.name()).isEqualTo("FK_SINGLE");
        assertThat(fk.fromColumns()).containsExactly("COMPANY_ID");
        assertThat(fk.toColumns()).containsExactly("ID");
        assertThat(fk.isComposite()).isFalse();
        assertThat(fk.updateAction()).isEqualTo("CASCADE");
    }

    @Test
    void compositeForeignKey_groupsRowsPreservingColumnOrder() throws SQLException {
        // two rows, same constraint name, declared column order A then B
        stubQuery(List.of(
                fkRow("FK_COMPOSITE", "REF_A", "KEY_A", false, false),
                fkRow("FK_COMPOSITE", "REF_B", "KEY_B", false, false)));

        List<ForeignKeyInfo> fks = service.extractForeignKeys("workcube_mikrolink");

        assertThat(fks).hasSize(1);
        ForeignKeyInfo fk = fks.get(0);
        assertThat(fk.isComposite()).isTrue();
        assertThat(fk.fromColumns()).containsExactly("REF_A", "REF_B");
        assertThat(fk.toColumns()).containsExactly("KEY_A", "KEY_B");
    }

    @Test
    void foreignKey_disabledAndUntrustedFlagsCarried() throws SQLException {
        stubQuery(List.of(fkRow("FK_FLAGS", "X_ID", "ID", true, true)));

        ForeignKeyInfo fk = service.extractForeignKeys("workcube_mikrolink").get(0);

        assertThat(fk.isDisabled()).isTrue();
        assertThat(fk.isNotTrusted()).isTrue();
    }

    // --- unique constraints ---

    private static ResultSet uqRow(String indexName, String table, String column,
                                   boolean isUniqueConstraint, String filter) throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("index_name")).thenReturn(indexName);
        when(r.getString("schema_name")).thenReturn("dbo");
        when(r.getString("table_name")).thenReturn(table);
        when(r.getString("column_name")).thenReturn(column);
        when(r.getBoolean("is_unique_constraint")).thenReturn(isUniqueConstraint);
        when(r.getString("filter_definition")).thenReturn(filter);
        return r;
    }

    @Test
    void uniqueConstraint_mappedAsUniqueConstraintType() throws SQLException {
        stubQuery(List.of(uqRow("UQ_CODE", "COMPANY", "COMPANY_SHORT_CODE", true, null)));

        List<UniqueConstraintInfo> ucs = service.extractUniqueConstraints("workcube_mikrolink");

        assertThat(ucs).hasSize(1);
        UniqueConstraintInfo uc = ucs.get(0);
        assertThat(uc.constraintType()).isEqualTo(UniqueConstraintType.UNIQUE_CONSTRAINT);
        assertThat(uc.columns()).containsExactly("COMPANY_SHORT_CODE");
        assertThat(uc.isComposite()).isFalse();
        assertThat(uc.isFiltered()).isFalse();
    }

    @Test
    void plainUniqueIndex_mappedAsUniqueIndexType() throws SQLException {
        stubQuery(List.of(uqRow("IX_UUID", "ORDERS", "ORDER_UUID", false, null)));

        UniqueConstraintInfo uc = service.extractUniqueConstraints("workcube_mikrolink").get(0);

        assertThat(uc.constraintType()).isEqualTo(UniqueConstraintType.UNIQUE_INDEX);
    }

    @Test
    void compositeUniqueConstraint_groupsRowsPreservingColumnOrder() throws SQLException {
        stubQuery(List.of(
                uqRow("UQ_COMPOSITE", "INVOICE", "COMPANY_ID", true, null),
                uqRow("UQ_COMPOSITE", "INVOICE", "INVOICE_NO", true, null)));

        List<UniqueConstraintInfo> ucs = service.extractUniqueConstraints("workcube_mikrolink");

        assertThat(ucs).hasSize(1);
        assertThat(ucs.get(0).isComposite()).isTrue();
        assertThat(ucs.get(0).columns()).containsExactly("COMPANY_ID", "INVOICE_NO");
    }

    @Test
    void filteredUniqueIndex_preservesFilterDefinition() throws SQLException {
        stubQuery(List.of(uqRow("IX_FILTERED", "ORDERS", "EXTERNAL_REF", false,
                "([EXTERNAL_REF] IS NOT NULL)")));

        UniqueConstraintInfo uc = service.extractUniqueConstraints("workcube_mikrolink").get(0);

        assertThat(uc.isFiltered()).isTrue();
        assertThat(uc.filterDefinition()).isEqualTo("([EXTERNAL_REF] IS NOT NULL)");
    }
}
