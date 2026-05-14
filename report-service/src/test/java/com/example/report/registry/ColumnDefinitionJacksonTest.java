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
    void invalidDefaultAggFuncRejected_garbageToken() {
        // Codex 019e2695 iter-5 absorb: PR #6a accepts `median`, so
        // the negative test now uses a permanently invalid token.
        // Roadmap functions (percentile, weightedavg) belong in
        // positive tests once their PRs land.
        String json = """
                {
                  "field": "X",
                  "headerName": "X",
                  "type": "number",
                  "width": 100,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "garbage_xyz"
                }
                """;

        assertThrows(Exception.class,
                () -> mapper.readValue(json, ColumnDefinition.class));
    }

    @Test
    void defaultAggFuncMedianAccepted() {
        // PR #6a positive case: median is a valid registry token.
        // Numeric-column constraint is enforced at the controller
        // sanitizeAggregations layer (not at Jackson deserialization),
        // so this happy-path test does not need to assert column type.
        String json = """
                {
                  "field": "AMOUNT",
                  "headerName": "Amount",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "median"
                }
                """;

        try {
            ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);
            assertEquals("median", cd.defaultAggFunc());
        } catch (Exception e) {
            throw new AssertionError("median must deserialize cleanly", e);
        }
    }

    @Test
    void defaultAggFuncMedianMixedCase_normalized() {
        String json = """
                {
                  "field": "AMOUNT",
                  "headerName": "Amount",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "MEDIAN"
                }
                """;
        try {
            ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);
            assertEquals("median", cd.defaultAggFunc(),
                    "mixed-case input must canonicalise to lower-case");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── Extended aggregate funcs (Codex thread 019e2695) ───────────────
    // PR-0.4z: registry-side opt-in for distinctcount / stddev / stddevp.
    // PR #6a: median accepted (numeric-only enforcement at controller layer).
    // PR #6b: percentilecont accepted with defaultAggParams.percentile
    // contract; weightedAvg (PR-0.4) remains on the roadmap.

    @Test
    void defaultAggFuncDistinctCountAccepted() throws Exception {
        String json = """
                {
                  "field": "USER_ID",
                  "headerName": "User",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "distinctcount"
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertEquals("distinctcount", cd.defaultAggFunc());
        assertTrue(cd.aggregatable());
    }

    @Test
    void defaultAggFuncStddevAccepted() throws Exception {
        String json = """
                {
                  "field": "AMOUNT",
                  "headerName": "Amount",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "stddev"
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertEquals("stddev", cd.defaultAggFunc());
    }

    @Test
    void defaultAggFuncStddevpAccepted() throws Exception {
        String json = """
                {
                  "field": "AMOUNT",
                  "headerName": "Amount",
                  "type": "number",
                  "width": 120,
                  "sensitive": false,
                  "aggregatable": true,
                  "defaultAggFunc": "STDDEVP"
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        // Mixed-case input normalizes to canonical lower-case form.
        assertEquals("stddevp", cd.defaultAggFunc());
    }

    // ── PR-0.4a (Codex 019e2695 hybrid pivot): pivotable column flag ───
    // The backend allowlist is authoritative for both server-mode (backend
    // pivot SQL) and client-mode (AG Grid native client pivot). Legacy
    // registry entries without the flag default to pivotable=false.

    @Test
    void pivotableFlagAccepted() throws Exception {
        String json = """
                {
                  "field": "ACTION_TYPE",
                  "headerName": "Action",
                  "type": "text",
                  "width": 120,
                  "sensitive": false,
                  "groupable": true,
                  "pivotable": true
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertEquals("ACTION_TYPE", cd.field());
        assertTrue(cd.pivotable(),
                "pivotable must round-trip through Jackson when explicitly set");
        assertTrue(cd.groupable(),
                "groupable flag in the same payload must stay populated");
    }

    @Test
    void pivotableFlagDefaultsFalseWhenMissing() throws Exception {
        // Legacy 5-field JSON: every existing report registry entry today.
        // The pivotable flag must default to false so registry files don't
        // need to be re-emitted just to opt out of pivot.
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

        assertFalse(cd.pivotable(),
                "pivotable must default to false for legacy 5-field JSON");
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

    // ── PR-0.4b (Codex 019e2695): pivotValues registry contract ────────
    //
    // The registry encodes per-pivot-bucket enumeration directly on the
    // pivot column. Two JSON shapes are supported: bare-string short form
    // (`pivotValues: ["A", "B"]`) where the SQL value doubles as the
    // label, and object form (`{"value": "A", "label": "Aktif"}`) for the
    // common case where the user-facing label differs from the SQL token.
    // Both shapes round-trip through Jackson without bespoke serializers.

    @Test
    void pivotValuesShortFormAccepted() throws Exception {
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": ["A", "B"]
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertTrue(cd.pivotable());
        assertNotNull(cd.pivotValues());
        assertEquals(2, cd.pivotValues().size());
        // Short form: label collapses to value so registry entries stay terse.
        assertEquals("A", cd.pivotValues().get(0).value());
        assertEquals("A", cd.pivotValues().get(0).label());
        assertEquals("B", cd.pivotValues().get(1).value());
    }

    @Test
    void pivotValuesObjectFormAccepted() throws Exception {
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": [
                    {"value": "A", "label": "Aktif"},
                    {"value": "P", "label": "Pasif"}
                  ]
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertNotNull(cd.pivotValues());
        assertEquals("A", cd.pivotValues().get(0).value());
        assertEquals("Aktif", cd.pivotValues().get(0).label());
        assertEquals("P", cd.pivotValues().get(1).value());
        assertEquals("Pasif", cd.pivotValues().get(1).label());
    }

    @Test
    void pivotValuesMixedFormsAccepted() throws Exception {
        // Registry can intermix shapes when only some entries need
        // explicit labels — Jackson resolves per-element via PivotValue's
        // delegating creator.
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": [
                    "A",
                    {"value": "P", "label": "Pasif"}
                  ]
                }
                """;

        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);

        assertEquals(2, cd.pivotValues().size());
        assertEquals("A", cd.pivotValues().get(0).label());
        assertEquals("Pasif", cd.pivotValues().get(1).label());
    }

    @Test
    void pivotValuesDuplicateRejected() {
        // Two registry entries that collapse to the same SQL value would
        // produce duplicate alias columns AG Grid cannot disambiguate.
        // Canonical constructor catches the collision at registry load
        // time rather than at query time.
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": ["A", "A"]
                }
                """;
        assertThrows(Exception.class,
                () -> mapper.readValue(json, ColumnDefinition.class));
    }

    @Test
    void pivotValuesAboveCapRejected() {
        // 9 > MAX_PIVOT_VALUES(8) → registry-time fail.
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": ["a","b","c","d","e","f","g","h","i"]
                }
                """;
        assertThrows(Exception.class,
                () -> mapper.readValue(json, ColumnDefinition.class));
    }

    @Test
    void pivotValuesDefaultsNullWhenMissing() throws Exception {
        // Legacy entry: pivotable flag present but no pivotValues. PR-0.4a
        // shipped this exact shape, so PR-0.4b must keep it deserializing
        // cleanly — runtime pivot dispatch will fall back to the
        // `PIVOT_NOT_CONFIGURED` 400 path.
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true
                }
                """;
        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);
        assertTrue(cd.pivotable());
        assertNull(cd.pivotValues());
    }

    @Test
    void pivotValuesEmptyArrayCollapsesToNull() throws Exception {
        // An empty `pivotValues: []` semantically equals "not configured";
        // canonical constructor collapses to null so equality/serialisation
        // matches the missing-field shape.
        String json = """
                {
                  "field": "STATUS",
                  "headerName": "Status",
                  "type": "text",
                  "width": 80,
                  "sensitive": false,
                  "pivotable": true,
                  "pivotValues": []
                }
                """;
        ColumnDefinition cd = mapper.readValue(json, ColumnDefinition.class);
        assertNull(cd.pivotValues());
    }
}
