package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ObjectInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-5 (capability M1 — Codex 019e3270): {@code extractObjects}
 * accumulator-grouping + SQL-shape tests. The mocked {@code jdbc.query}
 * replays hand-rolled rows through the {@code RowCallbackHandler}, locking
 * the multi-row → single {@link ObjectInfo} grouping (one row per extended
 * property, LEFT-JOIN null when none) and the field mapping. A separate
 * group captures the SQL string and asserts the {@code is_ms_shipped} /
 * type allowlist filter, the owner {@code COALESCE} fallback and the
 * {@code MS_Description}-free extended-property join. Actual {@code sys.*}
 * SQL execution correctness is the integration suite's job.
 */
class SchemaExtractServiceObjectExtractionTest {

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
     * Fluent builder for one {@code sys.objects} ⋈ {@code sys.extended_properties}
     * row — defaults model a plain user table with no extended property; each
     * test overrides only the fields it exercises.
     */
    private static final class Row {
        private String objectName = "INVOICE";
        private String objectType = "USER_TABLE";
        private int objectId = 100;
        private Timestamp createDate = Timestamp.valueOf("2020-01-01 10:00:00");
        private Timestamp modifyDate = Timestamp.valueOf("2021-06-15 14:30:00");
        private String owner = "dbo";
        private String epName = null;
        private String epValue = null;

        Row object(String v) { this.objectName = v; return this; }
        Row type(String v) { this.objectType = v; return this; }
        Row objectId(int v) { this.objectId = v; return this; }
        Row created(String ts) { this.createDate = Timestamp.valueOf(ts); return this; }
        Row modified(String ts) { this.modifyDate = Timestamp.valueOf(ts); return this; }
        Row owner(String v) { this.owner = v; return this; }
        Row extProp(String name, String value) {
            this.epName = name;
            this.epValue = value;
            return this;
        }

        ResultSet build() throws SQLException {
            ResultSet r = mock(ResultSet.class);
            when(r.getString("object_name")).thenReturn(objectName);
            when(r.getString("schema_name")).thenReturn("dbo");
            when(r.getString("object_type")).thenReturn(objectType);
            when(r.getInt("object_id")).thenReturn(objectId);
            when(r.getTimestamp("create_date")).thenReturn(createDate);
            when(r.getTimestamp("modify_date")).thenReturn(modifyDate);
            when(r.getString("owner_name")).thenReturn(owner);
            when(r.getString("ep_name")).thenReturn(epName);
            when(r.getString("ep_value")).thenReturn(epValue);
            return r;
        }
    }

    private List<ObjectInfo> extract(Row... rows) throws SQLException {
        List<ResultSet> rs = new ArrayList<>();
        for (Row row : rows) {
            rs.add(row.build());
        }
        stubQuery(rs);
        return service.extractObjects("workcube_mikrolink");
    }

    // --- field mapping ---

    @Test
    void userTable_mappedWithMetadata() throws SQLException {
        List<ObjectInfo> objects = extract(new Row().object("INVOICE")
                .type("USER_TABLE").objectId(245).owner("dbo"));

        assertThat(objects).hasSize(1);
        ObjectInfo o = objects.get(0);
        assertThat(o.name()).isEqualTo("INVOICE");
        assertThat(o.schema()).isEqualTo("dbo");
        assertThat(o.objectType()).isEqualTo("USER_TABLE");
        assertThat(o.objectId()).isEqualTo(245);
        assertThat(o.owner()).isEqualTo("dbo");
        assertThat(o.extendedProperties()).isEmpty();
    }

    @Test
    void objectTypes_viewProcFunctionTriggerSynonym_allCarried() throws SQLException {
        List<ObjectInfo> objects = extract(
                new Row().object("V_ORDERS").type("VIEW"),
                new Row().object("SP_BILL").type("SQL_STORED_PROCEDURE"),
                new Row().object("FN_TAX").type("SQL_SCALAR_FUNCTION"),
                new Row().object("TR_AUDIT").type("SQL_TRIGGER"),
                new Row().object("SYN_LEGACY").type("SYNONYM"));

        assertThat(objects).extracting(ObjectInfo::objectType)
                .containsExactlyInAnyOrder("VIEW", "SQL_STORED_PROCEDURE",
                        "SQL_SCALAR_FUNCTION", "SQL_TRIGGER", "SYNONYM");
    }

    @Test
    void createAndModifyDate_carriedAsLocalDateTime() throws SQLException {
        ObjectInfo o = extract(new Row().object("ORDERS")
                .created("2019-03-04 08:15:00")
                .modified("2024-11-20 23:59:59")).get(0);

        assertThat(o.createDate()).isEqualTo(LocalDateTime.of(2019, 3, 4, 8, 15, 0));
        assertThat(o.modifyDate()).isEqualTo(LocalDateTime.of(2024, 11, 20, 23, 59, 59));
    }

    // --- extended properties ---

    @Test
    void objectWithoutExtendedProperty_hasEmptyMapAndNullDescription() throws SQLException {
        // LEFT JOIN yields a single row with null ep_name.
        ObjectInfo o = extract(new Row().object("PLAIN_TABLE")).get(0);

        assertThat(o.extendedProperties()).isEmpty();
        assertThat(o.description()).isNull();
    }

    @Test
    void msDescription_exposedViaDescriptionConvenience() throws SQLException {
        ObjectInfo o = extract(new Row().object("INVOICE")
                .extProp("MS_Description", "Invoice header table")).get(0);

        assertThat(o.description()).isEqualTo("Invoice header table");
        assertThat(o.extendedProperties()).containsEntry("MS_Description", "Invoice header table");
    }

    @Test
    void multipleExtendedProperties_allCollectedKeySorted() throws SQLException {
        // Two rows, same object, distinct extended properties. The map is
        // key-sorted so snapshot artifacts stay deterministic regardless of
        // the order sys.extended_properties returns the rows.
        ObjectInfo o = extract(
                new Row().object("ORDERS").extProp("MS_Description", "Order header"),
                new Row().object("ORDERS").extProp("Custom_Tag", "audit-critical")).get(0);

        assertThat(o.extendedProperties())
                .containsEntry("MS_Description", "Order header")
                .containsEntry("Custom_Tag", "audit-critical")
                .hasSize(2);
        assertThat(o.extendedProperties().keySet())
                .containsExactly("Custom_Tag", "MS_Description");
        assertThat(o.description()).isEqualTo("Order header");
    }

    @Test
    void nonDescriptionExtendedProperty_descriptionStaysNull() throws SQLException {
        ObjectInfo o = extract(new Row().object("ORDERS")
                .extProp("Custom_Tag", "audit-critical")).get(0);

        assertThat(o.description()).isNull();
        assertThat(o.extendedProperties()).containsEntry("Custom_Tag", "audit-critical");
    }

    @Test
    void extendedPropertyWithNullValue_doesNotCollapseInventory() throws SQLException {
        // sys.extended_properties.value is sql_variant — it can be NULL.
        // A null value must reach the map, not NPE the extraction:
        // Map.copyOf rejects null values, so the inventory uses an
        // unmodifiable TreeMap instead.
        List<ObjectInfo> objects = extract(new Row().object("ORDERS")
                .extProp("Audit_Flag", null));

        assertThat(objects).hasSize(1);
        assertThat(objects.get(0).extendedProperties()).containsKey("Audit_Flag");
        assertThat(objects.get(0).extendedProperties().get("Audit_Flag")).isNull();
    }

    // --- grouping ---

    @Test
    void twoObjects_groupedSeparately() throws SQLException {
        List<ObjectInfo> objects = extract(
                new Row().object("INVOICE"),
                new Row().object("ORDERS"));

        assertThat(objects).extracting(ObjectInfo::name)
                .containsExactlyInAnyOrder("INVOICE", "ORDERS");
    }

    @Test
    void emptySchema_yieldsNoObjects() throws SQLException {
        stubQuery(List.of());
        assertThat(service.extractObjects("workcube_mikrolink")).isEmpty();
    }

    // --- SQL shape (Codex 019e3270: mocked rows cannot prove the WHERE /
    //     JOIN clauses — assert the SQL string directly) ---

    @Test
    void extractObjects_sqlFiltersToUserObjectsOnly() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("o.is_ms_shipped = 0")
                .contains("o.type IN ('U', 'V', 'P', 'FN', 'IF', 'TF', 'TR', 'SN')");
    }

    @Test
    void extractObjects_sqlResolvesOwnerAndExtendedProperties() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("COALESCE(o.principal_id, sch.principal_id)")
                .contains("ep.class = 1")
                .contains("ep.minor_id = 0")
                .contains("CAST(ep.value AS NVARCHAR(MAX))");
    }
}
