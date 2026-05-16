package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-2 (capability R1+R2 — Codex 019e2d7d, ADR-0020): wire-contract
 * tests for the authoritative constraint inventory models. Locks:
 * <ul>
 *   <li>{@link ForeignKeyInfo} / {@link UniqueConstraintInfo} loss-less
 *       round-trip incl. composite column order;</li>
 *   <li>derived {@code isComposite()} / {@code isFiltered()} stay out of
 *       the JSON ({@code @JsonIgnore});</li>
 *   <li>{@link SchemaSnapshot} legacy 6-arg constructor → empty inventory;</li>
 *   <li>8-arg snapshot serializes the inventory additively.</li>
 * </ul>
 */
class SchemaConstraintModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void foreignKeyInfo_roundTripPreservesCompositeOrder() throws Exception {
        ForeignKeyInfo fk = new ForeignKeyInfo(
                "FK_X",
                "dbo", "CHILD", List.of("REF_A", "REF_B"),
                "dbo", "PARENT", List.of("KEY_A", "KEY_B"),
                false, true, "NO_ACTION", "CASCADE");

        String json = mapper.writeValueAsString(fk);
        ForeignKeyInfo back = mapper.readValue(json, ForeignKeyInfo.class);

        assertThat(back).isEqualTo(fk);
        assertThat(back.fromColumns()).containsExactly("REF_A", "REF_B");
        assertThat(back.toColumns()).containsExactly("KEY_A", "KEY_B");
        assertThat(back.isComposite()).isTrue();
        // derived isComposite() must NOT leak into the wire contract
        assertThat(json).doesNotContain("\"composite\"");
    }

    @Test
    void uniqueConstraintInfo_roundTripPreservesTypeAndFilter() throws Exception {
        UniqueConstraintInfo uc = new UniqueConstraintInfo(
                "IX_FILTERED", "dbo", "ORDERS", List.of("EXTERNAL_REF"),
                UniqueConstraintType.UNIQUE_INDEX, "([EXTERNAL_REF] IS NOT NULL)");

        String json = mapper.writeValueAsString(uc);
        UniqueConstraintInfo back = mapper.readValue(json, UniqueConstraintInfo.class);

        assertThat(back).isEqualTo(uc);
        assertThat(back.constraintType()).isEqualTo(UniqueConstraintType.UNIQUE_INDEX);
        assertThat(back.isFiltered()).isTrue();
        assertThat(json).doesNotContain("\"composite\"").doesNotContain("\"filtered\"");
    }

    @Test
    void schemaSnapshot_legacyConstructor_yieldsEmptyInventories() {
        SchemaSnapshot snap = new SchemaSnapshot(
                "1.0",
                new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0),
                Map.of(), List.of(), Map.of(),
                new SchemaSnapshot.Analysis(List.of(), List.of()));

        assertThat(snap.foreignKeys()).isEmpty();
        assertThat(snap.uniqueConstraints()).isEmpty();
        assertThat(snap.checkConstraints()).isEmpty();
        assertThat(snap.defaultConstraints()).isEmpty();
        assertThat(snap.indexes()).isEmpty();
    }

    @Test
    void schemaSnapshot_serializesInventoryAdditively() throws Exception {
        ForeignKeyInfo fk = new ForeignKeyInfo(
                "FK_1", "dbo", "ORDERS", List.of("COMPANY_ID"),
                "dbo", "COMPANY", List.of("ID"),
                false, false, "NO_ACTION", "NO_ACTION");
        UniqueConstraintInfo uc = new UniqueConstraintInfo(
                "UQ_1", "dbo", "COMPANY", List.of("CODE"),
                UniqueConstraintType.UNIQUE_CONSTRAINT, null);
        SchemaSnapshot snap = new SchemaSnapshot(
                "1.1",
                new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0),
                Map.of(), List.of(), List.of(fk), List.of(uc), Map.of(),
                new SchemaSnapshot.Analysis(List.of(), List.of()));

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"foreignKeys\"").contains("\"uniqueConstraints\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.foreignKeys()).hasSize(1);
        assertThat(back.uniqueConstraints()).hasSize(1);
        assertThat(back.foreignKeys().get(0).name()).isEqualTo("FK_1");
        assertThat(back.uniqueConstraints().get(0).constraintType())
                .isEqualTo(UniqueConstraintType.UNIQUE_CONSTRAINT);
    }

    // --- B1-3 / M3: check + default constraint inventory ---

    @Test
    void checkConstraintInfo_roundTripPreservesTableLevelNull() throws Exception {
        CheckConstraintInfo tableLevel = new CheckConstraintInfo(
                "CK_DATES", "dbo", "INVOICE", null,
                "([START_DATE]<[END_DATE])", false, true);

        String json = mapper.writeValueAsString(tableLevel);
        CheckConstraintInfo back = mapper.readValue(json, CheckConstraintInfo.class);

        assertThat(back).isEqualTo(tableLevel);
        assertThat(back.columnName()).isNull();
        assertThat(back.isTableLevel()).isTrue();
        // derived isTableLevel() must NOT leak into the wire contract
        assertThat(json).doesNotContain("\"tableLevel\"");
    }

    @Test
    void defaultConstraintInfo_roundTrip() throws Exception {
        DefaultConstraintInfo dc = new DefaultConstraintInfo(
                "DF_INVOICE_STATUS", "dbo", "INVOICE", "STATUS", "((1))");

        String json = mapper.writeValueAsString(dc);
        DefaultConstraintInfo back = mapper.readValue(json, DefaultConstraintInfo.class);

        assertThat(back).isEqualTo(dc);
        assertThat(back.name()).isEqualTo("DF_INVOICE_STATUS");
        assertThat(back.definition()).isEqualTo("((1))");
    }

    @Test
    void schemaSnapshot_serializesCheckAndDefaultInventoryAdditively() throws Exception {
        CheckConstraintInfo cc = new CheckConstraintInfo(
                "CK_AMOUNT", "dbo", "INVOICE", "AMOUNT", "([AMOUNT]>=(0))", false, false);
        DefaultConstraintInfo dc = new DefaultConstraintInfo(
                "DF_STATUS", "dbo", "INVOICE", "STATUS", "((1))");
        SchemaSnapshot snap = new SchemaSnapshot(
                "1.1",
                new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(cc), List.of(dc), Map.of(),
                new SchemaSnapshot.Analysis(List.of(), List.of()));

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"checkConstraints\"").contains("\"defaultConstraints\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.checkConstraints()).hasSize(1);
        assertThat(back.defaultConstraints()).hasSize(1);
        assertThat(back.checkConstraints().get(0).name()).isEqualTo("CK_AMOUNT");
        assertThat(back.defaultConstraints().get(0).columnName()).isEqualTo("STATUS");
    }
}
