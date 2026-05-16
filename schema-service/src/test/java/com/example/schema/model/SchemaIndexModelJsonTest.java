package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-4 (capability M4 — Codex 019e325a, ADR-0020): wire-contract tests
 * for the authoritative {@link IndexInfo} inventory. Locks:
 * <ul>
 *   <li>loss-less round-trip incl. {@link IndexInfo.KeyColumn} ordinal +
 *       descending and the key / included column split;</li>
 *   <li>derived {@code isFiltered()} / {@code isComposite()} stay out of the
 *       JSON ({@code @JsonIgnore});</li>
 *   <li>{@code has_filter=true} with a {@code null} predicate still round-trips
 *       as filtered;</li>
 *   <li>{@link SchemaSnapshot} 11-arg constructor serializes {@code indexes}
 *       additively; the new legacy 10-arg constructor yields an empty list.</li>
 * </ul>
 */
class SchemaIndexModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void indexInfo_roundTripPreservesKeyOrderAndIncludedColumns() throws Exception {
        IndexInfo idx = new IndexInfo(
                "IX_COVER", "dbo", "ORDERS", "NONCLUSTERED",
                List.of(new IndexInfo.KeyColumn("COMPANY_ID", 1, false),
                        new IndexInfo.KeyColumn("ORDER_DATE", 2, true)),
                List.of("TOTAL", "STATUS"),
                false, false, false, false, null, 80, false, false);

        String json = mapper.writeValueAsString(idx);
        IndexInfo back = mapper.readValue(json, IndexInfo.class);

        assertThat(back).isEqualTo(idx);
        assertThat(back.keyColumns()).extracting(IndexInfo.KeyColumn::name)
                .containsExactly("COMPANY_ID", "ORDER_DATE");
        assertThat(back.keyColumns()).extracting(IndexInfo.KeyColumn::ordinal)
                .containsExactly(1, 2);
        assertThat(back.keyColumns().get(1).descending()).isTrue();
        assertThat(back.includedColumns()).containsExactly("TOTAL", "STATUS");
        assertThat(back.isComposite()).isTrue();
        assertThat(back.fillFactor()).isEqualTo(80);
        // derived isComposite()/isFiltered() must NOT leak into the wire contract
        assertThat(json).doesNotContain("\"composite\"").doesNotContain("\"filtered\"");
    }

    @Test
    void indexInfo_hasFilterTrueWithNullDefinition_roundTripsAsFiltered() throws Exception {
        IndexInfo idx = new IndexInfo(
                "IX_FILTERED", "dbo", "ORDERS", "NONCLUSTERED",
                List.of(new IndexInfo.KeyColumn("EXTERNAL_REF", 1, false)),
                List.of(), false, false, false,
                true, null, 0, false, false);

        String json = mapper.writeValueAsString(idx);
        IndexInfo back = mapper.readValue(json, IndexInfo.class);

        assertThat(back).isEqualTo(idx);
        assertThat(back.hasFilter()).isTrue();
        assertThat(back.filterDefinition()).isNull();
        assertThat(back.isFiltered()).isTrue();
    }

    @Test
    void indexInfo_flagsRoundTrip() throws Exception {
        IndexInfo idx = new IndexInfo(
                "PK_ORDERS", "dbo", "ORDERS", "CLUSTERED",
                List.of(new IndexInfo.KeyColumn("ID", 1, false)),
                List.of(), true, true, false,
                false, null, 0, true, true);

        IndexInfo back = mapper.readValue(mapper.writeValueAsString(idx), IndexInfo.class);

        assertThat(back.isUnique()).isTrue();
        assertThat(back.isPrimaryKey()).isTrue();
        assertThat(back.isUniqueConstraint()).isFalse();
        assertThat(back.isDisabled()).isTrue();
        assertThat(back.isHypothetical()).isTrue();
    }

    @Test
    void keyColumn_roundTrip() throws Exception {
        IndexInfo.KeyColumn kc = new IndexInfo.KeyColumn("CREATED_AT", 3, true);

        IndexInfo.KeyColumn back =
                mapper.readValue(mapper.writeValueAsString(kc), IndexInfo.KeyColumn.class);

        assertThat(back).isEqualTo(kc);
        assertThat(back.ordinal()).isEqualTo(3);
        assertThat(back.descending()).isTrue();
    }

    @Test
    void schemaSnapshot_serializesIndexInventoryAdditively() throws Exception {
        IndexInfo idx = new IndexInfo(
                "IX_1", "dbo", "ORDERS", "NONCLUSTERED",
                List.of(new IndexInfo.KeyColumn("ORDER_DATE", 1, false)),
                List.of(), false, false, false, false, null, 0, false, false);
        SchemaSnapshot snap = new SchemaSnapshot(
                "1.1",
                new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(idx), Map.of(),
                new SchemaSnapshot.Analysis(List.of(), List.of()));

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"indexes\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.indexes()).hasSize(1);
        assertThat(back.indexes().get(0).name()).isEqualTo("IX_1");
        assertThat(back.indexes().get(0).keyColumns().get(0).name()).isEqualTo("ORDER_DATE");
    }

    @Test
    void schemaSnapshot_legacy10ArgConstructor_yieldsEmptyIndexes() {
        SchemaSnapshot snap = new SchemaSnapshot(
                "1.1",
                new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), new SchemaSnapshot.Analysis(List.of(), List.of()));

        assertThat(snap.indexes()).isEmpty();
    }
}
