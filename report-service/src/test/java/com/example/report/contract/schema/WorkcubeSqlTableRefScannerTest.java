package com.example.report.contract.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.schema.TableRef.SchemaKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 11.2a — Lightweight scanner unit tests (Adım 11.2a).
 *
 * <p>Codex {@code 019e258f} iter-20 absorb: parser must support bracketed
 * + unbracketed two-part refs, mask string literals + comments, classify
 * schema kinds (placeholder, canonical, yearly, current, unknown), and
 * fail-closed on unsupported targets.
 */
class WorkcubeSqlTableRefScannerTest {

    @Test
    void bracketed_two_part_with_placeholder_schema() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "SELECT * FROM [{schema}].[CARI_ROWS] WITH (NOLOCK)");
        assertThat(refs).hasSize(1);
        TableRef r = refs.get(0);
        assertThat(r.table()).isEqualTo("CARI_ROWS");
        assertThat(r.schema()).isEqualTo("{schema}");
        assertThat(r.schemaKind()).isEqualTo(SchemaKind.PLACEHOLDER);
    }

    @Test
    void bracketed_two_part_with_canonical_schema() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "FROM [workcube_mikrolink].[COMPANY] C WITH (NOLOCK)");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.CANONICAL);
        assertThat(refs.get(0).table()).isEqualTo("COMPANY");
    }

    @Test
    void bracketed_two_part_with_yearly_partition_schema() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "FROM [workcube_mikrolink_2026_35].[INVOICE]");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.YEARLY_PARTITION);
    }

    @Test
    void bracketed_two_part_with_current_tenant_schema() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "FROM [workcube_mikrolink_35].[OUR_COMPANY]");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.CURRENT_TENANT);
    }

    @Test
    void multi_table_join_query_extracts_all_refs() {
        String sql = "SELECT * FROM [{schema}].[INVOICE_ROW] IR WITH (NOLOCK) "
                + "LEFT JOIN [{schema}].[INVOICE] I ON I.INVOICE_ID = IR.INVOICE_ID "
                + "LEFT JOIN [workcube_mikrolink].[COMPANY] C ON C.COMPANY_ID = I.COMPANY_ID";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(3);
        assertThat(refs).extracting(TableRef::table)
                .containsExactlyInAnyOrder("INVOICE_ROW", "INVOICE", "COMPANY");
    }

    @Test
    void string_literal_does_not_produce_phantom_ref() {
        // 'FROM [X]' inside CASE/string MUST NOT surface as a table ref
        String sql = "SELECT CASE WHEN x = 'FROM [PHANTOM]' THEN 1 ELSE 0 END "
                + "FROM [{schema}].[CARI_ROWS]";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).table()).isEqualTo("CARI_ROWS");
    }

    @Test
    void line_comment_does_not_produce_phantom_ref() {
        String sql = "SELECT * FROM [{schema}].[CARI_ROWS] -- LEFT JOIN [{schema}].[PHANTOM]";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
    }

    @Test
    void block_comment_does_not_produce_phantom_ref() {
        String sql = "SELECT * FROM [{schema}].[CARI_ROWS] /* JOIN [{schema}].[PHANTOM] */";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
    }

    @Test
    void temp_table_target_marked_unknown_for_fail_closed() {
        String sql = "SELECT * FROM #tempData WHERE id > 0";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.UNKNOWN);
        assertThat(refs.get(0).raw()).contains("#tempData");
    }

    @Test
    void table_variable_target_marked_unknown_for_fail_closed() {
        String sql = "SELECT * FROM @myTableVar";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.UNKNOWN);
    }

    @Test
    void openquery_target_marked_unknown_for_fail_closed() {
        String sql = "SELECT * FROM OPENQUERY(LINKED, 'SELECT * FROM remote')";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.UNKNOWN);
    }

    @Test
    void empty_or_null_input_returns_empty() {
        assertThat(WorkcubeSqlTableRefScanner.scan(null)).isEmpty();
        assertThat(WorkcubeSqlTableRefScanner.scan("")).isEmpty();
        assertThat(WorkcubeSqlTableRefScanner.scan("   ")).isEmpty();
    }

    @Test
    void unbracketed_two_part_after_keyword() {
        String sql = "SELECT * FROM workcube_mikrolink.COMPANY";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).table()).isEqualTo("COMPANY");
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.CANONICAL);
    }

    @Test
    void table_uppercase_normalisation() {
        // Lower/mixed-case identifiers normalise to UPPERCASE for V1 lookup
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "FROM [workcube_mikrolink].[company]");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).table()).isEqualTo("COMPANY");
    }

    // ---- Codex iter-21 REVISE-1: unqualified target coverage --------

    @Test
    void unqualified_from_target_marked_unqualified() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "SELECT * FROM ACCOUNT_CARD_ROWS WITH (NOLOCK)");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.UNQUALIFIED);
        assertThat(refs.get(0).table()).isEqualTo("ACCOUNT_CARD_ROWS");
    }

    @Test
    void unqualified_join_target_marked_unqualified() {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "SELECT * FROM [{schema}].[INVOICE] I "
                        + "LEFT JOIN SECRET_TABLE S ON S.ID = I.ID");
        assertThat(refs).hasSize(2);
        assertThat(refs).extracting(TableRef::schemaKind)
                .contains(SchemaKind.PLACEHOLDER, SchemaKind.UNQUALIFIED);
    }

    @Test
    void unqualified_does_not_match_inside_two_part_ref() {
        // After bracketed match, the scanner should not double-count
        // the embedded table name as unqualified.
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(
                "FROM [{schema}].[CARI_ROWS]");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).schemaKind()).isEqualTo(SchemaKind.PLACEHOLDER);
    }

    @Test
    void position_metadata_tracks_source_offset() {
        // Position tracks character offset for diagnostic error messages
        String prefix = "SELECT * ";
        String sql = prefix + "FROM [{schema}].[CARI_ROWS]";
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sql);
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).position()).isGreaterThanOrEqualTo(prefix.length());
    }
}
