package com.example.report.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-1 (capability M2 — Codex 019e2d7d): schema-service expanded its
 * {@code ColumnInfo} from 7 to 16 fields. This report-service mirror only
 * deserializes {@code name} / {@code dataType} / {@code nullable}; the test
 * proves the {@code @JsonIgnoreProperties(ignoreUnknown = true)} guard on
 * {@link SchemaSnapshot} keeps the mirror working when the upstream wire
 * shape grows additively — no mirror code change is required.
 */
class SchemaSnapshotColumnMirrorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandedSchemaServiceColumn_deserializesWithoutBreakingMirror() throws Exception {
        // schema-service B1-1 16-field ColumnInfo wire shape
        String json = """
            {"name":"AMOUNT","dataType":"decimal","maxLength":9,
             "precision":18,"scale":4,"collation":null,
             "nullable":false,"identity":false,
             "identitySeed":null,"identityIncrement":null,"pk":true,
             "defaultExpression":"((0))","computedExpression":null,
             "computedPersisted":false,"sparse":false,"ordinal":3}
            """;

        SchemaSnapshot.ColumnInfo mirror =
                mapper.readValue(json, SchemaSnapshot.ColumnInfo.class);

        // mirror keeps its 3 fields; the 13 new B1-1 fields are ignored
        assertThat(mirror.name()).isEqualTo("AMOUNT");
        assertThat(mirror.dataType()).isEqualTo("decimal");
        assertThat(mirror.nullable()).isFalse();
    }

    @Test
    void expandedColumn_insideFullSnapshot_deserializes() throws Exception {
        String json = """
            {"tables":{"INVOICE":{"name":"INVOICE","schema":"workcube_mikrolink",
             "columns":[
               {"name":"ID","dataType":"int","maxLength":4,"precision":10,
                "scale":0,"collation":null,"nullable":false,"identity":true,
                "identitySeed":1,"identityIncrement":1,"pk":true,
                "defaultExpression":null,"computedExpression":null,
                "computedPersisted":false,"sparse":false,"ordinal":1}
             ]}}}
            """;

        SchemaSnapshot snap = mapper.readValue(json, SchemaSnapshot.class);

        assertThat(snap.tables()).containsKey("INVOICE");
        SchemaSnapshot.TableInfo invoice = snap.tables().get("INVOICE");
        assertThat(invoice.columns()).hasSize(1);
        SchemaSnapshot.ColumnInfo id = invoice.columns().get(0);
        assertThat(id.name()).isEqualTo("ID");
        assertThat(id.dataType()).isEqualTo("int");
        assertThat(id.nullable()).isFalse();
    }

    @Test
    void legacySevenFieldColumn_stillDeserializes() throws Exception {
        // pre-B1-1 wire shape (no new fields) must keep working
        String json = """
            {"name":"CODE","dataType":"nvarchar","maxLength":40,
             "nullable":true,"identity":false,"pk":false,"ordinal":2}
            """;

        SchemaSnapshot.ColumnInfo mirror =
                mapper.readValue(json, SchemaSnapshot.ColumnInfo.class);

        assertThat(mirror.name()).isEqualTo("CODE");
        assertThat(mirror.dataType()).isEqualTo("nvarchar");
        assertThat(mirror.nullable()).isTrue();
    }

    @Test
    void b1TopLevelInventoryFields_ignoredByMirror() throws Exception {
        // schema-service B1-2/B1-3/B1-4 add top-level `foreignKeys` /
        // `uniqueConstraints` / `checkConstraints` / `defaultConstraints` /
        // `indexes`. The mirror only reads `tables`, so these new top-level
        // fields must be silently ignored — no mirror code change required
        // (@JsonIgnoreProperties(ignoreUnknown=true)).
        String json = """
            {"version":"1.1",
             "tables":{"ORDERS":{"name":"ORDERS","schema":"workcube_mikrolink",
               "columns":[{"name":"ID","dataType":"int","maxLength":4,
                "nullable":false,"identity":true,"pk":true,"ordinal":1}]}},
             "relationships":[],
             "foreignKeys":[{"name":"FK_1","fromSchema":"dbo","fromTable":"ORDERS",
               "fromColumns":["COMPANY_ID"],"toSchema":"dbo","toTable":"COMPANY",
               "toColumns":["ID"],"isDisabled":false,"isNotTrusted":false,
               "deleteAction":"NO_ACTION","updateAction":"NO_ACTION"}],
             "uniqueConstraints":[{"name":"UQ_1","schema":"dbo","table":"COMPANY",
               "columns":["CODE"],"constraintType":"UNIQUE_INDEX","filterDefinition":null}],
             "checkConstraints":[{"name":"CK_1","schema":"dbo","table":"ORDERS",
               "columnName":"AMOUNT","definition":"([AMOUNT]>=(0))",
               "isDisabled":false,"isNotTrusted":false}],
             "defaultConstraints":[{"name":"DF_1","schema":"dbo","table":"ORDERS",
               "columnName":"STATUS","definition":"((1))"}],
             "indexes":[{"name":"IX_1","schema":"dbo","table":"ORDERS",
               "indexType":"NONCLUSTERED",
               "keyColumns":[{"name":"ORDER_DATE","ordinal":1,"descending":false}],
               "includedColumns":["TOTAL"],"isUnique":false,"isPrimaryKey":false,
               "isUniqueConstraint":false,"hasFilter":false,"filterDefinition":null,
               "fillFactor":0,"isDisabled":false,"isHypothetical":false}],
             "domains":{}}
            """;

        SchemaSnapshot snap = mapper.readValue(json, SchemaSnapshot.class);

        assertThat(snap.tables()).containsKey("ORDERS");
        assertThat(snap.tables().get("ORDERS").columns()).hasSize(1);
    }
}
