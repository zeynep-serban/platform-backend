package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.ReportingContractColumn;
import com.example.schema.model.ReportingContractSnapshot;
import com.example.schema.model.ReportingContractTable;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Adım 12 — {@link ReportingContractService} unit coverage.
 *
 * <p>The service projects the full {@link SchemaSnapshot} down to the
 * allowlist-filtered {@link ReportingContractSnapshot}. These tests pin:
 * <ul>
 *   <li>only {@code ReportingAllowlist} tables survive the filter</li>
 *   <li>{@code tables} is deterministically ordered by (schema, name)</li>
 *   <li>columns are ordinal-ordered + {@code dataType → type} mapped</li>
 *   <li>{@code contract_version} / {@code allowlist_*} provenance fields</li>
 *   <li>empty intersection → empty {@code tables} (controller maps 404)</li>
 * </ul>
 */
class ReportingContractServiceTest {

    private static ColumnInfo col(String name, String dataType, boolean nullable, int ordinal) {
        return new ColumnInfo(name, dataType, 0, nullable, false, false, ordinal);
    }

    private static SchemaSnapshot snapshotOf(Map<String, TableInfo> tables) {
        return SchemaSnapshot.builder()
            .version("1.0")
            .metadata(new SchemaSnapshot.Metadata("mssql", "h", "d", "s", Instant.now(),
                tables.size(), 0, 0, 0))
            .tables(tables)
            .relationships(List.<Relationship>of())
            .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
            .build();
    }

    private ReportingContractService serviceReturning(Map<String, TableInfo> tables) {
        SchemaSnapshotService snapshotService = mock(SchemaSnapshotService.class);
        when(snapshotService.buildSnapshot(anyString())).thenReturn(snapshotOf(tables));
        return new ReportingContractService(snapshotService, "1");
    }

    @Test
    void buildContract_filters_to_allowlist_tables_only() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("INVOICE", new TableInfo("INVOICE", "dbo",
            List.of(col("ID", "int", false, 1))));
        // Not in ReportingAllowlist.V1 — must be dropped.
        tables.put("RANDOM_JUNK", new TableInfo("RANDOM_JUNK", "dbo",
            List.of(col("X", "int", false, 1))));
        tables.put("ORDERS", new TableInfo("ORDERS", "dbo",
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        assertThat(contract.tables())
            .extracting(ReportingContractTable::name)
            .containsExactly("INVOICE", "ORDERS"); // sorted, junk dropped
    }

    @Test
    void buildContract_sorts_tables_by_schema_then_name() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        // Insertion order deliberately scrambled.
        tables.put("ORDERS", new TableInfo("ORDERS", "dbo",
            List.of(col("ID", "int", false, 1))));
        tables.put("INVOICE", new TableInfo("INVOICE", "dbo",
            List.of(col("ID", "int", false, 1))));
        tables.put("BRANCH", new TableInfo("BRANCH", "acc",
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        // acc.BRANCH first (schema "acc" < "dbo"), then dbo.INVOICE, dbo.ORDERS.
        assertThat(contract.tables())
            .extracting(t -> t.schema() + "." + t.name())
            .containsExactly("acc.BRANCH", "dbo.INVOICE", "dbo.ORDERS");
    }

    @Test
    void buildContract_maps_dataType_to_type_and_orders_columns_by_ordinal() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        // Columns inserted out of ordinal order.
        tables.put("INVOICE", new TableInfo("INVOICE", "dbo", List.of(
            col("AMOUNT", "decimal(18,2)", true, 3),
            col("ID", "int", false, 1),
            col("CREATED", "datetime", true, 2)
        )));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        List<ReportingContractColumn> columns = contract.tables().get(0).columns();
        assertThat(columns)
            .extracting(ReportingContractColumn::name)
            .containsExactly("ID", "CREATED", "AMOUNT"); // ordinal 1,2,3
        assertThat(columns)
            .extracting(ReportingContractColumn::type)
            .containsExactly("int", "datetime", "decimal(18,2)");
        assertThat(columns.get(0).nullable()).isFalse();
        assertThat(columns.get(1).nullable()).isTrue();
    }

    @Test
    void buildContract_carries_provenance_fields() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("INVOICE", new TableInfo("INVOICE", "dbo",
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        assertThat(contract.contractVersion()).isEqualTo("1");
        assertThat(contract.allowlistName()).isEqualTo("ReportingAllowlist");
        assertThat(contract.allowlistVersion()).isEqualTo("V1");
    }

    @Test
    void buildContract_empty_intersection_yields_empty_tables() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        // Nothing in the allowlist.
        tables.put("RANDOM_JUNK", new TableInfo("RANDOM_JUNK", "dbo",
            List.of(col("X", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        assertThat(contract.tables()).isEmpty();
        // Provenance still present so the consumer's required-field
        // checks pass before it hits the empty-table fail-closed path.
        assertThat(contract.contractVersion()).isEqualTo("1");
    }

    @Test
    void buildContract_table_name_match_is_case_insensitive() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        // Lowercase table name still matches the uppercase allowlist.
        tables.put("invoice", new TableInfo("invoice", "dbo",
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        assertThat(contract.tables()).hasSize(1);
        assertThat(contract.tables().get(0).name()).isEqualTo("invoice");
    }

    @Test
    void buildContract_null_schema_falls_back_to_target_schema() {
        // Codex 019e2d64 post-impl P2: a null TableInfo.schema (legacy
        // extraction edge case) resolves to the target schema the
        // caller requested — that is the schema the table came from,
        // and it keeps the wire field a non-empty string.
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("INVOICE", new TableInfo("INVOICE", null,
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink");

        assertThat(contract.tables().get(0).schema()).isEqualTo("workcube_mikrolink");
    }

    @Test
    void buildContract_blank_schema_falls_back_to_target_schema() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("INVOICE", new TableInfo("INVOICE", "   ",
            List.of(col("ID", "int", false, 1))));

        ReportingContractSnapshot contract = serviceReturning(tables)
            .buildContract("workcube_mikrolink_2025");

        assertThat(contract.tables().get(0).schema()).isEqualTo("workcube_mikrolink_2025");
    }

    @Test
    void buildContract_honours_configured_contract_version() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("INVOICE", new TableInfo("INVOICE", "dbo",
            List.of(col("ID", "int", false, 1))));

        SchemaSnapshotService snapshotService = mock(SchemaSnapshotService.class);
        when(snapshotService.buildSnapshot(anyString())).thenReturn(snapshotOf(tables));
        ReportingContractService svc = new ReportingContractService(snapshotService, "2");

        assertThat(svc.buildContract("s").contractVersion()).isEqualTo("2");
    }
}
