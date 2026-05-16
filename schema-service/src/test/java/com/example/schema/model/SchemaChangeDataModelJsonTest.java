package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-7 (capability M13 — Codex 019e32aa, ADR-0020): wire-contract tests
 * for the authoritative {@link ChangeDataInfo} inventory — loss-less round-trip
 * incl. the nullable Change Tracking version fields, the derived
 * {@code replicated()} convenience kept out of the JSON ({@code @JsonIgnore}),
 * and additive {@code changeData} serialization on {@link SchemaSnapshot}.
 */
class SchemaChangeDataModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void changeDataInfo_roundTripPreservesAllFeatureFlags() throws Exception {
        ChangeDataInfo cd = new ChangeDataInfo(
                "EMPLOYEE", "dbo", true, true, true,
                900L, 100L, 80L,
                "SYSTEM_VERSIONED_TEMPORAL_TABLE", "history", "EMPLOYEE_HISTORY",
                true, false, false, true);

        String json = mapper.writeValueAsString(cd);
        ChangeDataInfo back = mapper.readValue(json, ChangeDataInfo.class);

        assertThat(back).isEqualTo(cd);
        assertThat(back.cdcEnabled()).isTrue();
        assertThat(back.ctMinValidVersion()).isEqualTo(900L);
        assertThat(back.temporalType()).isEqualTo("SYSTEM_VERSIONED_TEMPORAL_TABLE");
        assertThat(back.historyTable()).isEqualTo("EMPLOYEE_HISTORY");
        assertThat(back.replicated()).isTrue();
        // derived replicated() must NOT leak into the wire contract
        assertThat(json).doesNotContain("\"replicated\"");
    }

    @Test
    void changeDataInfo_nullVersionFields_roundTrip() throws Exception {
        // CDC-only table — Change Tracking disabled, version fields null.
        ChangeDataInfo cd = new ChangeDataInfo(
                "ORDERS", "dbo", true, false, false,
                null, null, null,
                "NON_TEMPORAL_TABLE", null, null,
                false, false, false, false);

        ChangeDataInfo back = mapper.readValue(mapper.writeValueAsString(cd), ChangeDataInfo.class);

        assertThat(back).isEqualTo(cd);
        assertThat(back.ctMinValidVersion()).isNull();
        assertThat(back.historyTable()).isNull();
        assertThat(back.replicated()).isFalse();
    }

    @Test
    void schemaSnapshot_serializesChangeDataAdditively() throws Exception {
        ChangeDataInfo cd = new ChangeDataInfo(
                "INVOICE", "dbo", false, true, false,
                500L, 10L, 5L, "NON_TEMPORAL_TABLE", null, null,
                false, false, false, false);
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .changeData(List.of(cd))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        String json = mapper.writeValueAsString(snap);
        assertThat(json).contains("\"changeData\"");

        SchemaSnapshot back = mapper.readValue(json, SchemaSnapshot.class);
        assertThat(back.changeData()).hasSize(1);
        assertThat(back.changeData().get(0).table()).isEqualTo("INVOICE");
        assertThat(back.changeData().get(0).changeTrackingEnabled()).isTrue();
    }

    @Test
    void schemaSnapshot_builderDefaults_yieldEmptyChangeData() {
        SchemaSnapshot snap = SchemaSnapshot.builder()
                .version("1.1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "", "", "s", Instant.now(), 0, 0, 0, 0))
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();

        assertThat(snap.changeData()).isEmpty();
    }
}
