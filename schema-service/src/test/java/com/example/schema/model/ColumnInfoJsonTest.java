package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-1 (capability M2 — Codex 019e2d7d): {@link ColumnInfo} expanded
 * from 7 to 16 fields. These tests lock the wire contract:
 * <ul>
 *   <li>new fields serialize in camelCase (additive {@code /snapshot} growth);</li>
 *   <li>the legacy 7-arg constructor leaves new fields null/false;</li>
 *   <li>full 16-field round-trip is loss-less.</li>
 * </ul>
 */
class ColumnInfoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandedColumnInfo_serializesNewFieldsInCamelCase() throws Exception {
        ColumnInfo col = new ColumnInfo(
                "AMOUNT", "decimal", 9,
                18, 4, null,
                false, false, null, null,
                true, "((0))", null, false, false,
                3);

        String json = mapper.writeValueAsString(col);

        assertThat(json)
                .contains("\"precision\":18")
                .contains("\"scale\":4")
                .contains("\"defaultExpression\":\"((0))\"")
                .contains("\"computedExpression\":null")
                .contains("\"computedPersisted\":false")
                .contains("\"sparse\":false")
                .contains("\"identitySeed\":null")
                .contains("\"identityIncrement\":null")
                .contains("\"collation\":null");
    }

    @Test
    void legacySevenArgConstructor_newFieldsDefaultNullOrFalse() {
        ColumnInfo col = new ColumnInfo("ID", "int", 4, false, true, true, 1);

        // new B1-1 fields → not extracted / not applicable
        assertThat(col.precision()).isNull();
        assertThat(col.scale()).isNull();
        assertThat(col.collation()).isNull();
        assertThat(col.identitySeed()).isNull();
        assertThat(col.identityIncrement()).isNull();
        assertThat(col.defaultExpression()).isNull();
        assertThat(col.computedExpression()).isNull();
        assertThat(col.computedPersisted()).isFalse();
        assertThat(col.sparse()).isFalse();

        // legacy fields preserved
        assertThat(col.name()).isEqualTo("ID");
        assertThat(col.dataType()).isEqualTo("int");
        assertThat(col.maxLength()).isEqualTo(4);
        assertThat(col.nullable()).isFalse();
        assertThat(col.identity()).isTrue();
        assertThat(col.pk()).isTrue();
        assertThat(col.ordinal()).isEqualTo(1);
    }

    @Test
    void fullSixteenField_roundTripIsLossLess() throws Exception {
        ColumnInfo original = new ColumnInfo(
                "TOTAL", "numeric", 13,
                28, 8, "SQL_Latin1_General_CP1_CI_AS",
                true, false, null, null,
                false, null, "([QTY]*[PRICE])", true, true,
                7);

        String json = mapper.writeValueAsString(original);
        ColumnInfo back = mapper.readValue(json, ColumnInfo.class);

        assertThat(back).isEqualTo(original);
    }

    @Test
    void identityColumn_seedAndIncrementSerialized() throws Exception {
        ColumnInfo col = new ColumnInfo(
                "ID", "bigint", 8,
                19, 0, null,
                false, true, 1L, 1L,
                true, null, null, false, false,
                1);

        String json = mapper.writeValueAsString(col);
        assertThat(json)
                .contains("\"identitySeed\":1")
                .contains("\"identityIncrement\":1")
                .contains("\"identity\":true");

        ColumnInfo back = mapper.readValue(json, ColumnInfo.class);
        assertThat(back.identitySeed()).isEqualTo(1L);
        assertThat(back.identityIncrement()).isEqualTo(1L);
    }
}
