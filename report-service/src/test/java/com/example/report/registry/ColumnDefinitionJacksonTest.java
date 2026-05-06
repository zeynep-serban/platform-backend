package com.example.report.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * PR-0.2 Codex iter-1 absorb — backward-compatibility coverage for the
 * 3 new optional fields on {@link ColumnDefinition} (groupable,
 * aggregatable, defaultAggFunc).
 *
 * <p>The report registry deserializes JSON via Jackson 2's record support,
 * which uses the canonical (8-arg) constructor under the hood. Existing
 * registry entries pre-PR-0.2 only carry the original 5 fields, so we
 * need explicit proof that Jackson populates the 3 new fields with
 * sensible defaults rather than raising
 * {@code MissingKotlinParameterException}-style errors at startup.
 */
class ColumnDefinitionJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializeLegacy5FieldJson_appliesPr02Defaults() throws Exception {
        // Shape that matches every JSON registry entry shipped before
        // PR-0.2 — no groupable / aggregatable / defaultAggFunc keys.
        String legacy = """
                {
                  "field": "ACCOUNT_CODE",
                  "headerName": "Hesap Kodu",
                  "type": "text",
                  "width": 140,
                  "sensitive": false
                }
                """;

        ColumnDefinition cd = mapper.readValue(legacy, ColumnDefinition.class);

        assertEquals("ACCOUNT_CODE", cd.field());
        assertEquals("Hesap Kodu", cd.headerName());
        assertEquals("text", cd.type());
        assertEquals(140, cd.width());
        assertFalse(cd.sensitive());
        // Critical: PR-0.2 fields must default to false / null without
        // forcing every legacy registry entry to be re-emitted.
        assertFalse(cd.groupable(), "groupable must default to false");
        assertFalse(cd.aggregatable(), "aggregatable must default to false");
        assertNull(cd.defaultAggFunc(), "defaultAggFunc must default to null");
    }

    @Test
    void deserializeFullPr02Json_populatesNewFields() throws Exception {
        String full = """
                {
                  "field": "AMOUNT",
                  "headerName": "Tutar",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "groupable": false,
                  "aggregatable": true,
                  "defaultAggFunc": "sum"
                }
                """;

        ColumnDefinition cd = mapper.readValue(full, ColumnDefinition.class);

        assertEquals("AMOUNT", cd.field());
        assertFalse(cd.groupable());
        assertTrue(cd.aggregatable());
        assertEquals("sum", cd.defaultAggFunc());
    }

    @Test
    void defaultAggFuncMixedCase_normalizedToLowerCase() throws Exception {
        // Registry might be hand-edited; case-insensitive validation
        // keeps minor typos from blocking deploy.
        String json = """
                {
                  "field": "QTY",
                  "headerName": "Qty",
                  "type": "number",
                  "width": 100,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "AVG"
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertEquals("avg", cd.defaultAggFunc(),
                "defaultAggFunc is normalized to lower-case so SqlBuilder "
                        + "comparison stays canonical");
    }

    @Test
    void invalidDefaultAggFuncRejected() {
        // Hand-edited typo ("median" isn't supported) must fail loudly
        // at registry load — registry catches the ResponseStatusException
        // analogue and skips the report, but we want the validation to
        // run rather than silently accept garbage.
        String json = """
                {
                  "field": "X",
                  "headerName": "X",
                  "type": "number",
                  "width": 100,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "median"
                }
                """;

        assertThrows(Exception.class,
                () -> mapper.readValue(json, ColumnDefinition.class));
    }

    @Test
    void backwardCompatConstructor5ArgEqualsLegacyDeserialization() throws Exception {
        // The 5-arg secondary constructor is what most existing
        // production code paths still call. It must produce the same
        // ColumnDefinition shape as deserializing the legacy JSON
        // through Jackson — otherwise we have two divergent code paths.
        ColumnDefinition viaCtor =
                new ColumnDefinition("F", "Field", "text", 100, false);
        String json = """
                {"field":"F","headerName":"Field","type":"text","width":100,"sensitive":false}
                """;
        ColumnDefinition viaJson = mapper.readValue(json, ColumnDefinition.class);

        // Records' equals() uses every component, so this transitively
        // checks groupable / aggregatable / defaultAggFunc parity.
        assertEquals(viaCtor, viaJson);
        assertNotNull(viaCtor.toString());
    }
}
