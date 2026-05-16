package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-5 (capability M1 — Codex 019e3270, ADR-0020): wire-contract tests
 * for the authoritative {@link ObjectInfo} catalog. Locks:
 * <ul>
 *   <li>loss-less round-trip incl. the {@code extendedProperties} map and the
 *       {@link LocalDateTime} create / modify dates;</li>
 *   <li>derived {@code description()} stays out of the JSON ({@code @JsonIgnore})
 *       and reads {@code MS_Description} from the property map;</li>
 *   <li>a builder-set {@link SchemaSnapshot} serializes {@code objects}
 *       additively; {@link SchemaSnapshot.Builder} defaults yield an empty list.</li>
 * </ul>
 */
class SchemaObjectModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void objectInfo_roundTripPreservesExtendedPropertiesAndDates() throws Exception {
        ObjectInfo obj = new ObjectInfo(
                "INVOICE", "dbo", "USER_TABLE", 245, "dbo",
                LocalDateTime.of(2019, 3, 4, 8, 15, 0),
                LocalDateTime.of(2024, 11, 20, 23, 59, 59),
                Map.of("MS_Description", "Invoice header", "Custom_Tag", "audit-critical"));

        String json = mapper.writeValueAsString(obj);
        ObjectInfo back = mapper.readValue(json, ObjectInfo.class);

        assertThat(back).isEqualTo(obj);
        assertThat(back.objectId()).isEqualTo(245);
        assertThat(back.createDate()).isEqualTo(LocalDateTime.of(2019, 3, 4, 8, 15, 0));
        assertThat(back.modifyDate()).isEqualTo(LocalDateTime.of(2024, 11, 20, 23, 59, 59));
        assertThat(back.extendedProperties())
                .containsEntry("MS_Description", "Invoice header")
                .containsEntry("Custom_Tag", "audit-critical");
        // derived description() must NOT leak into the wire contract
        assertThat(json).doesNotContain("\"description\"");
    }

    @Test
    void objectInfo_descriptionConvenience_readsMsDescription() throws Exception {
        ObjectInfo obj = new ObjectInfo(
                "ORDERS", "dbo", "USER_TABLE", 100, "dbo",
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 1, 0, 0),
                Map.of("MS_Description", "Order header"));

        ObjectInfo back = mapper.readValue(mapper.writeValueAsString(obj), ObjectInfo.class);

        assertThat(back.description()).isEqualTo("Order header");
    }

    @Test
    void objectInfo_emptyExtendedProperties_descriptionNull() throws Exception {
        ObjectInfo obj = new ObjectInfo(
                "PLAIN", "dbo", "VIEW", 7, "dbo",
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 1, 0, 0),
                Map.of());

        ObjectInfo back = mapper.readValue(mapper.writeValueAsString(obj), ObjectInfo.class);

        assertThat(back.extendedProperties()).isEmpty();
        assertThat(back.description()).isNull();
    }

    @Test
    void schemaSnapshot_serializesObjectInventoryAdditively() throws Exception {
        ObjectInfo obj = new ObjectInfo(
                "INVOICE", "dbo", "USER_TABLE", 245, "dbo",
                LocalDateTime.of(2020, 1, 1, 10, 0), LocalDateTime.of(2021, 6, 15, 14, 30),
                Map.of("MS_Description", "Invoice header"));
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .objects(List.of(obj))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"objects\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.objects()).hasSize(1);
        assertThat(back.objects().get(0).name()).isEqualTo("INVOICE");
        assertThat(back.objects().get(0).description()).isEqualTo("Invoice header");
    }

    @Test
    void schemaSnapshot_builderDefaults_yieldEmptyObjects() {
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        assertThat(snap.objects()).isEmpty();
    }
}
