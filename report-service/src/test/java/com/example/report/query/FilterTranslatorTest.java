package com.example.report.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FilterTranslator — AG Grid filter model to SQL WHERE translation.
 * SK-7 coverage target.
 */
class FilterTranslatorTest {

    private FilterTranslator translator;
    private final Set<String> allowed = Set.of("name", "amount", "created_at", "status");

    @BeforeEach
    void setUp() {
        translator = new FilterTranslator();
    }

    @Test
    void translate_null_returnsEmptyClause() {
        var result = translator.translate(null, allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_empty_returnsEmptyClause() {
        var result = translator.translate(Map.of(), allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_disallowedColumn_skipped() {
        var result = translator.translate(
                Map.of("secret_col", Map.of("type", "equals", "filter", "x")),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_setFilter_producesInClause() {
        var result = translator.translate(
                Map.of("status", Map.of("filterType", "set", "values", List.of("ACTIVE", "PENDING"))),
                allowed);
        assertTrue(result.whereClause().contains("[status] IN"));
    }

    @Test
    void translate_setFilter_emptyValues_returnsEmpty() {
        var result = translator.translate(
                Map.of("status", Map.of("filterType", "set", "values", List.of())),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_contains_producesLike() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "contains", "filter", "test")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
        assertTrue(result.params().hasValue("p1"));
    }

    @Test
    void translate_equals_producesEquals() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "equals", "filter", "exact")),
                allowed);
        assertTrue(result.whereClause().contains("[name] = :"));
    }

    @Test
    void translate_notEqual_producesNotEqual() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "notEqual", "filter", "x")),
                allowed);
        assertTrue(result.whereClause().contains("[name] <> :"));
    }

    @Test
    void translate_greaterThan() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "greaterThan", "filter", 100)),
                allowed);
        assertTrue(result.whereClause().contains("[amount] > :"));
    }

    @Test
    void translate_lessThanOrEqual() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "lessThanOrEqual", "filter", 50)),
                allowed);
        assertTrue(result.whereClause().contains("[amount] <= :"));
    }

    @Test
    void translate_inRange_producesBetween() {
        var result = translator.translate(
                Map.of("amount", Map.of("type", "inRange", "filter", 10, "filterTo", 100)),
                allowed);
        assertTrue(result.whereClause().contains("BETWEEN"));
    }

    @Test
    void translate_blank_producesIsNull() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "blank")),
                allowed);
        assertTrue(result.whereClause().contains("IS NULL"));
    }

    @Test
    void translate_notBlank_producesIsNotNull() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "notBlank")),
                allowed);
        assertTrue(result.whereClause().contains("IS NOT NULL"));
    }

    @Test
    void translate_multipleFilters_joinsWithAnd() {
        var result = translator.translate(Map.of(
                "name", Map.of("type", "contains", "filter", "a"),
                "amount", Map.of("type", "greaterThan", "filter", 10)
        ), allowed);
        assertTrue(result.whereClause().contains("AND"));
    }

    @Test
    void translate_unknownType_skipped() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "unknownOp", "filter", "x")),
                allowed);
        assertEquals("", result.whereClause());
    }

    @Test
    void translate_startsWith() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "startsWith", "filter", "A")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
    }

    @Test
    void translate_endsWith() {
        var result = translator.translate(
                Map.of("name", Map.of("type", "endsWith", "filter", "Z")),
                allowed);
        assertTrue(result.whereClause().contains("[name] LIKE"));
    }

    // ── PR #5a: Compound AND/OR parser (Codex thread 019e2695) ────────
    // AG Grid SSRM emits compound filters with `operator` + either a
    // legacy `condition1`/`condition2` pair or a modern `conditions[]`
    // array. Backend must parse both recursively so PR #5b's compound
    // ancestor merge has a real downstream consumer. Compound shape:
    //   { operator: "AND"|"OR", condition1, condition2 }
    //   { operator: "AND"|"OR", conditions: [...] }

    @Test
    void translate_compoundAnd_legacyTwoSlotShape() {
        // {col: {operator: AND, condition1: {type: equals, filter: "FIN"},
        //                       condition2: {type: contains, filter: "FI"}}}
        var compound = Map.of(
                "operator", "AND",
                "condition1", Map.of("type", "equals", "filter", "FIN"),
                "condition2", Map.of("type", "contains", "filter", "FI"));
        var result = translator.translate(Map.of("name", compound), allowed);

        assertTrue(result.whereClause().startsWith("("),
                "compound filter must wrap in parentheses for precedence");
        assertTrue(result.whereClause().endsWith(")"),
                "compound filter must close parentheses");
        assertTrue(result.whereClause().contains("[name] = :"),
                "equals branch should emit '[col] = :p'");
        assertTrue(result.whereClause().contains("[name] LIKE :"),
                "contains branch should emit '[col] LIKE :p'");
        assertTrue(result.whereClause().contains(" AND "),
                "operator AND must join the two clauses");
    }

    @Test
    void translate_compoundOr_legacyTwoSlotShape() {
        var compound = Map.of(
                "operator", "OR",
                "condition1", Map.of("type", "equals", "filter", "FIN"),
                "condition2", Map.of("type", "equals", "filter", "HR"));
        var result = translator.translate(Map.of("name", compound), allowed);

        assertTrue(result.whereClause().contains(" OR "),
                "operator OR must join the two clauses");
        assertTrue(result.whereClause().startsWith("("),
                "compound filter must wrap in parentheses");
    }

    @Test
    void translate_compoundAnd_conditionsArrayShape() {
        // Modern AG Grid shape: {operator: AND, conditions: [c1, c2, c3]}
        var compound = new java.util.LinkedHashMap<String, Object>();
        compound.put("operator", "AND");
        compound.put("conditions", List.of(
                Map.of("type", "equals", "filter", "FIN"),
                Map.of("type", "contains", "filter", "FI"),
                Map.of("type", "notBlank")));
        var result = translator.translate(Map.of("name", compound), allowed);

        // Three clauses joined by AND
        int andCount = result.whereClause().split(" AND ").length - 1;
        assertEquals(2, andCount, "conditions[] of size 3 must produce two AND joiners");
        assertTrue(result.whereClause().contains("[name] IS NOT NULL"),
                "notBlank branch should emit IS NOT NULL");
    }

    @Test
    void translate_compoundNested_outerAndInnerOrPreservesParentheses() {
        // Outer AND with an inner OR compound:
        //   ancestor equals "FIN" AND (user equals "FIN" OR user contains "HR")
        // The inner OR must keep its own parentheses so precedence is
        // explicit on the SQL side.
        var innerOr = Map.of(
                "operator", "OR",
                "condition1", Map.of("type", "equals", "filter", "FIN"),
                "condition2", Map.of("type", "contains", "filter", "HR"));
        var outerAnd = Map.of(
                "operator", "AND",
                "condition1", Map.of("type", "equals", "filter", "FIN"),
                "condition2", innerOr);
        var result = translator.translate(Map.of("name", outerAnd), allowed);

        // Expected SQL shape: (c1 AND (cN OR cM)) — outer paren wraps
        // the AND join, inner paren wraps the OR join. The "AND ("
        // substring proves the outer joiner is followed by a nested
        // compound, and "))" proves both compounds close in sequence.
        assertTrue(result.whereClause().contains(" AND ("),
                "outer AND must precede the inner compound's open paren");
        assertTrue(result.whereClause().contains(" OR "),
                "inner OR operator must reach the SQL");
        assertTrue(result.whereClause().endsWith("))"),
                "nested compound must close inner + outer parens in sequence");
    }

    @Test
    void translate_compoundUnknownOperator_returnsNullClause() {
        // Defensive: an unknown operator (XOR, NAND, vb.) is dropped
        // silently rather than producing a half-built clause. Same
        // semantics as the existing `translate_unknownType_skipped`
        // path: drop, don't fail.
        var compound = Map.of(
                "operator", "XOR",
                "condition1", Map.of("type", "equals", "filter", "FIN"),
                "condition2", Map.of("type", "equals", "filter", "HR"));
        var result = translator.translate(Map.of("name", compound), allowed);

        assertEquals("", result.whereClause(),
                "unknown compound operator must drop without producing SQL");
    }

    @Test
    void translate_simpleFilterWithStrayOperatorMetadata_stillUsesSimplePath() {
        // Codex 019e2695 iter-3 absorb: an `operator` key alone must
        // not divert a simple filter into the compound branch. The
        // dispatch requires `operator` AND at least one compound child
        // slot (conditions[], condition1, condition2); otherwise the
        // simple-filter switch runs as before. A regression here would
        // silently drop every simple filter on AG Grid versions that
        // always emit `operator` metadata.
        var filterWithStrayOperator = new java.util.HashMap<String, Object>();
        filterWithStrayOperator.put("type", "equals");
        filterWithStrayOperator.put("filter", "FIN");
        filterWithStrayOperator.put("operator", "AND"); // stray; no condition1/2/conditions

        var result = translator.translate(
                Map.of("name", filterWithStrayOperator), allowed);

        assertTrue(result.whereClause().contains("[name] = :"),
                "simple filter must keep working when operator key is "
                        + "present but no compound child slot is provided");
        assertFalse(result.whereClause().contains("("),
                "no parenthesised compound output for the simple-path case");
    }

    @Test
    void translate_compoundDepthCapExceeded_dropsClauseSilently() {
        // Codex 019e2695 iter-3 absorb: a request-controlled payload
        // cannot push the parser past MAX_COMPOUND_DEPTH (16). Build
        // a 20-level single-child nested chain so each level has no
        // sibling simple clause to fall back to — once the cap fires
        // the innermost null cascades all the way up and the whole
        // entry is skipped from the top-level join.
        Map<String, Object> nested = Map.of("type", "equals", "filter", "X");
        for (int i = 0; i < 20; i++) {
            nested = Map.of(
                    "operator", "AND",
                    "conditions", List.of(nested));
        }

        var result = translator.translate(Map.of("name", nested), allowed);

        // No simple-clause siblings → depth cap nullifies the whole
        // entry. Stack overflow proof: this loop completes without a
        // StackOverflowError because the parser bails out at depth 16
        // rather than walking the full 20-level chain.
        assertEquals("", result.whereClause(),
                "depth cap must drop the entire over-nested compound entry");
    }

    @Test
    void translate_compoundConditionsArray_filtersInvalidEntriesSilently() {
        // If one of the conditions is malformed (e.g. unknown type),
        // the recursive parser drops it but the rest are still joined.
        var compound = new java.util.LinkedHashMap<String, Object>();
        compound.put("operator", "AND");
        compound.put("conditions", List.of(
                Map.of("type", "equals", "filter", "FIN"),
                Map.of("type", "unknown_xyz", "filter", "ignored"),
                Map.of("type", "notBlank")));
        var result = translator.translate(Map.of("name", compound), allowed);

        // Two valid clauses survive, joined by a single AND
        assertTrue(result.whereClause().contains("[name] = :"));
        assertTrue(result.whereClause().contains("[name] IS NOT NULL"));
        int andCount = result.whereClause().split(" AND ").length - 1;
        assertEquals(1, andCount, "two valid conditions yield one AND joiner");
    }
}
