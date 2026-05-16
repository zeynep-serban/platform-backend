package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.IndexInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-4 (capability M4 — Codex 019e325a): {@code extractIndexes}
 * accumulator-grouping + SQL-shape tests. The mocked {@code jdbc.query}
 * replays hand-rolled rows through the {@code RowCallbackHandler}, locking
 * the multi-row → single {@link IndexInfo} grouping (composite key order,
 * key vs included split, flag mapping). A separate group captures the SQL
 * string and asserts the heap / rowstore filter and that PK /
 * unique-constraint / disabled / hypothetical indexes are NOT filtered out —
 * they must reach the inventory flagged (B0 §5.3 "ayrı işaret"). Actual
 * {@code sys.*} SQL execution correctness is the integration suite's job.
 */
class SchemaExtractServiceIndexExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private String capturedSql;

    private void stubQuery(List<ResultSet> rows) {
        // jdbc.query(sql, params, RowCallbackHandler) is void → doAnswer.
        doAnswer(inv -> {
            capturedSql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            for (ResultSet row : rows) {
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    /**
     * Fluent builder for one {@code sys.index_columns} ResultSet row — the
     * defaults model a plain single-column nonclustered key column; each test
     * overrides only the fields it exercises.
     */
    private static final class Row {
        private String indexName = "IX_T_COL";
        private String table = "T";
        private String typeDesc = "NONCLUSTERED";
        private String column = "COL";
        private int keyOrdinal = 1;
        private boolean included = false;
        private boolean descending = false;
        private boolean isUnique = false;
        private boolean isPrimaryKey = false;
        private boolean isUniqueConstraint = false;
        private boolean hasFilter = false;
        private String filter = null;
        private int fillFactor = 0;
        private boolean disabled = false;
        private boolean hypothetical = false;

        Row index(String v) { this.indexName = v; return this; }
        Row table(String v) { this.table = v; return this; }
        Row type(String v) { this.typeDesc = v; return this; }
        Row column(String v) { this.column = v; return this; }
        Row keyOrdinal(int v) { this.keyOrdinal = v; return this; }
        Row included() { this.included = true; this.keyOrdinal = 0; return this; }
        Row descending() { this.descending = true; return this; }
        Row unique() { this.isUnique = true; return this; }
        Row primaryKey() { this.isPrimaryKey = true; return this; }
        Row uniqueConstraint() { this.isUniqueConstraint = true; return this; }
        Row filtered(String predicate) { this.hasFilter = true; this.filter = predicate; return this; }
        Row hasFilterNoDefinition() { this.hasFilter = true; this.filter = null; return this; }
        Row fillFactor(int v) { this.fillFactor = v; return this; }
        Row disabled() { this.disabled = true; return this; }
        Row hypothetical() { this.hypothetical = true; return this; }

        ResultSet build() throws SQLException {
            ResultSet r = mock(ResultSet.class);
            when(r.getString("index_name")).thenReturn(indexName);
            when(r.getString("schema_name")).thenReturn("dbo");
            when(r.getString("table_name")).thenReturn(table);
            when(r.getString("index_type")).thenReturn(typeDesc);
            when(r.getString("column_name")).thenReturn(column);
            when(r.getInt("key_ordinal")).thenReturn(keyOrdinal);
            when(r.getBoolean("is_included_column")).thenReturn(included);
            when(r.getBoolean("is_descending_key")).thenReturn(descending);
            when(r.getBoolean("is_unique")).thenReturn(isUnique);
            when(r.getBoolean("is_primary_key")).thenReturn(isPrimaryKey);
            when(r.getBoolean("is_unique_constraint")).thenReturn(isUniqueConstraint);
            when(r.getBoolean("has_filter")).thenReturn(hasFilter);
            when(r.getString("filter_definition")).thenReturn(filter);
            when(r.getInt("fill_factor")).thenReturn(fillFactor);
            when(r.getBoolean("is_disabled")).thenReturn(disabled);
            when(r.getBoolean("is_hypothetical")).thenReturn(hypothetical);
            return r;
        }
    }

    private List<IndexInfo> extract(Row... rows) throws SQLException {
        List<ResultSet> rs = new ArrayList<>();
        for (Row row : rows) {
            rs.add(row.build());
        }
        stubQuery(rs);
        return service.extractIndexes("workcube_mikrolink");
    }

    // --- grouping / column mapping ---

    @Test
    void nonclusteredIndex_singleKeyColumn() throws SQLException {
        List<IndexInfo> idx = extract(new Row().index("IX_ORDERS_DATE")
                .table("ORDERS").column("ORDER_DATE"));

        assertThat(idx).hasSize(1);
        IndexInfo ix = idx.get(0);
        assertThat(ix.name()).isEqualTo("IX_ORDERS_DATE");
        assertThat(ix.table()).isEqualTo("ORDERS");
        assertThat(ix.indexType()).isEqualTo("NONCLUSTERED");
        assertThat(ix.keyColumns()).hasSize(1);
        assertThat(ix.keyColumns().get(0).name()).isEqualTo("ORDER_DATE");
        assertThat(ix.keyColumns().get(0).ordinal()).isEqualTo(1);
        assertThat(ix.keyColumns().get(0).descending()).isFalse();
        assertThat(ix.includedColumns()).isEmpty();
        assertThat(ix.isComposite()).isFalse();
    }

    @Test
    void clusteredPrimaryKeyIndex_carriesPrimaryKeyFlag() throws SQLException {
        IndexInfo ix = extract(new Row().index("PK_ORDERS").table("ORDERS")
                .type("CLUSTERED").column("ID").unique().primaryKey()).get(0);

        assertThat(ix.indexType()).isEqualTo("CLUSTERED");
        assertThat(ix.isPrimaryKey()).isTrue();
        assertThat(ix.isUnique()).isTrue();
    }

    @Test
    void compositeIndex_preservesKeyOrdinalAndOrder() throws SQLException {
        IndexInfo ix = extract(
                new Row().index("IX_COMPOSITE").table("INVOICE")
                        .column("COMPANY_ID").keyOrdinal(1),
                new Row().index("IX_COMPOSITE").table("INVOICE")
                        .column("INVOICE_NO").keyOrdinal(2)).get(0);

        assertThat(ix.isComposite()).isTrue();
        assertThat(ix.keyColumns()).extracting(IndexInfo.KeyColumn::name)
                .containsExactly("COMPANY_ID", "INVOICE_NO");
        assertThat(ix.keyColumns()).extracting(IndexInfo.KeyColumn::ordinal)
                .containsExactly(1, 2);
    }

    @Test
    void descendingKeyColumn_carriesDescendingFlag() throws SQLException {
        IndexInfo ix = extract(new Row().index("IX_DESC").table("LOG")
                .column("CREATED_AT").descending()).get(0);

        assertThat(ix.keyColumns().get(0).descending()).isTrue();
    }

    @Test
    void includedColumns_separatedFromKeyColumns() throws SQLException {
        IndexInfo ix = extract(
                new Row().index("IX_COVER").table("ORDERS")
                        .column("COMPANY_ID").keyOrdinal(1),
                new Row().index("IX_COVER").table("ORDERS")
                        .column("TOTAL").included(),
                new Row().index("IX_COVER").table("ORDERS")
                        .column("STATUS").included()).get(0);

        assertThat(ix.keyColumns()).extracting(IndexInfo.KeyColumn::name)
                .containsExactly("COMPANY_ID");
        assertThat(ix.includedColumns()).containsExactly("TOTAL", "STATUS");
    }

    // --- filtered / disabled / hypothetical: never silently dropped ---

    @Test
    void filteredIndex_carriesHasFilterAndPredicate() throws SQLException {
        IndexInfo ix = extract(new Row().index("IX_FILTERED").table("ORDERS")
                .column("EXTERNAL_REF").filtered("([EXTERNAL_REF] IS NOT NULL)")).get(0);

        assertThat(ix.hasFilter()).isTrue();
        assertThat(ix.filterDefinition()).isEqualTo("([EXTERNAL_REF] IS NOT NULL)");
        assertThat(ix.isFiltered()).isTrue();
    }

    @Test
    void filteredIndex_hasFilterTrueButNullDefinition_stillFiltered() throws SQLException {
        // filter_definition can read NULL under restricted metadata
        // visibility — has_filter is the authoritative signal.
        IndexInfo ix = extract(new Row().index("IX_FILTERED_NODEF").table("ORDERS")
                .column("EXTERNAL_REF").hasFilterNoDefinition()).get(0);

        assertThat(ix.hasFilter()).isTrue();
        assertThat(ix.filterDefinition()).isNull();
        assertThat(ix.isFiltered()).isTrue();
    }

    @Test
    void disabledIndex_carriedNotDropped() throws SQLException {
        List<IndexInfo> idx = extract(new Row().index("IX_DISABLED")
                .table("ORDERS").column("LEGACY_COL").disabled());

        assertThat(idx).hasSize(1);
        assertThat(idx.get(0).isDisabled()).isTrue();
    }

    @Test
    void hypotheticalIndex_carriedWithFlag() throws SQLException {
        // DTA (Database Tuning Advisor) artifact — flagged, not eliminated.
        IndexInfo ix = extract(new Row().index("_dta_index_hint").table("ORDERS")
                .column("COL").hypothetical()).get(0);

        assertThat(ix.isHypothetical()).isTrue();
    }

    @Test
    void uniqueConstraintBackedIndex_carriesFlags() throws SQLException {
        IndexInfo ix = extract(new Row().index("UQ_COMPANY_CODE").table("COMPANY")
                .column("CODE").unique().uniqueConstraint()).get(0);

        assertThat(ix.isUnique()).isTrue();
        assertThat(ix.isUniqueConstraint()).isTrue();
        assertThat(ix.isPrimaryKey()).isFalse();
    }

    @Test
    void fillFactorCarried() throws SQLException {
        IndexInfo ix = extract(new Row().index("IX_FF").table("ORDERS")
                .column("COL").fillFactor(80)).get(0);

        assertThat(ix.fillFactor()).isEqualTo(80);
    }

    @Test
    void heapTable_yieldsNoIndexes() throws SQLException {
        // A heap table contributes no rows (index_id=0 filtered by the SQL);
        // extractIndexes must return an empty list, not fail.
        stubQuery(List.of());
        assertThat(service.extractIndexes("workcube_mikrolink")).isEmpty();
    }

    @Test
    void twoIndexesOnSameTable_groupedSeparately() throws SQLException {
        List<IndexInfo> idx = extract(
                new Row().index("IX_A").table("ORDERS").column("COL_A"),
                new Row().index("IX_B").table("ORDERS").column("COL_B"));

        assertThat(idx).extracting(IndexInfo::name)
                .containsExactlyInAnyOrder("IX_A", "IX_B");
    }

    // --- SQL shape (Codex 019e325a rev #4: mocked rows cannot prove the
    //     WHERE / SELECT clauses — assert the SQL string directly) ---

    @Test
    void extractIndexes_sqlExcludesHeapAndNonRowstore() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("i.index_id > 0")
                .contains("i.type IN (1, 2)");
    }

    @Test
    void extractIndexes_sqlDoesNotFilterOutPkUniqueDisabledHypothetical() throws SQLException {
        // B0 §5.3: PK / unique-constraint / disabled / hypothetical indexes
        // must reach the inventory flagged — the SQL must NOT exclude them.
        extract(new Row());

        assertThat(capturedSql)
                .doesNotContain("is_primary_key = 0")
                .doesNotContain("is_unique_constraint = 0")
                .doesNotContain("is_disabled = 0")
                .doesNotContain("is_hypothetical = 0");
    }

    @Test
    void extractIndexes_sqlSelectsAllInventoryColumns() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("i.is_primary_key")
                .contains("i.is_unique_constraint")
                .contains("i.is_unique")
                .contains("i.has_filter")
                .contains("i.filter_definition")
                .contains("i.fill_factor")
                .contains("i.is_disabled")
                .contains("i.is_hypothetical")
                .contains("ic.is_descending_key")
                .contains("ic.key_ordinal");
    }
}
