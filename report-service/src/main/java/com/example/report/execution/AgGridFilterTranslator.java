package com.example.report.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Translates AG-Grid filter model to the downstream-shaped
 * {@code {logic, conditions: [{field, op, value, value2?}]}} payload
 * that user-service / permission-service {@code decodeAdvancedFilter}
 * understands (PR-D2.1c2, ADR-0015).
 *
 * <p>Codex 019e8306 iter-6 (cross-AI peer review) verified the
 * downstream contract by reading user-service source:
 * {@code UserControllerV1.buildAdvancedFilterSpecSafe} (lines 626-714)
 * supports exactly seven operators ({@code equals}, {@code notEqual},
 * {@code contains}, {@code notContains}, {@code lessThan},
 * {@code greaterThan}, {@code inRange}) and a flat root logic
 * ({@code and} / {@code or}). Nested logic and set-filter array
 * values are NOT supported by the downstream parser — anything else
 * fails closed here (faz 1).
 *
 * <h2>AG-Grid → downstream operator mapping</h2>
 *
 * <table>
 *   <tr><th>AG-Grid {@code type}</th><th>Downstream {@code op}</th></tr>
 *   <tr><td>{@code contains}</td><td>{@code contains}</td></tr>
 *   <tr><td>{@code notContains}</td><td>{@code notContains}</td></tr>
 *   <tr><td>{@code equals}</td><td>{@code equals}</td></tr>
 *   <tr><td>{@code notEqual}</td><td>{@code notEqual}</td></tr>
 *   <tr><td>{@code lessThan}</td><td>{@code lessThan}</td></tr>
 *   <tr><td>{@code greaterThan}</td><td>{@code greaterThan}</td></tr>
 *   <tr><td>{@code inRange}</td><td>{@code inRange}
 *       (value = filter, value2 = filterTo)</td></tr>
 * </table>
 *
 * <h2>Rejected shapes (faz 1, c2.5 candidate)</h2>
 *
 * <ul>
 *   <li>AG-Grid {@code startsWith} / {@code endsWith} — not in
 *       downstream parser switch.</li>
 *   <li>AG-Grid {@code lessThanOrEqual} / {@code greaterThanOrEqual} —
 *       downstream only has strict {@code lessThan} / {@code greaterThan}.</li>
 *   <li>AG-Grid {@code blank} / {@code notBlank} — no downstream
 *       op.</li>
 *   <li>AG-Grid {@code filterType: "set"} (multi-value dropdown) —
 *       downstream doesn't accept array values; needs an {@code op:"in"}
 *       in the downstream parser first.</li>
 *   <li>AG-Grid multi-condition per field
 *       ({@code {condition1, condition2, operator}}) — downstream has
 *       flat root logic only; nested condition trees would silently
 *       degrade meaning ({@code (A OR B) AND C} can't be expressed as
 *       {@code A AND B AND C}).</li>
 * </ul>
 *
 * <p>All rejected shapes throw {@link IllegalArgumentException} with a
 * specific message; the controller layer maps that to a structured
 * {@code 400 REMOTE_FILTER_UNSUPPORTED} response so the frontend can
 * surface a deterministic error.
 */
@Component
public class AgGridFilterTranslator {

    /**
     * AG-Grid type → downstream op map. Keys lowercased to make the
     * lookup case-insensitive (AG-Grid sometimes uses camelCase, the
     * downstream parser only accepts lowercase strings).
     */
    private static final Map<String, String> OP_MAP = Map.of(
            "contains", "contains",
            "notcontains", "notContains",
            "equals", "equals",
            "notequal", "notEqual",
            "lessthan", "lessThan",
            "greaterthan", "greaterThan",
            "inrange", "inRange"
    );

    /**
     * AG-Grid types we explicitly know about but do not support. Listed
     * for fail-closed messaging — anything else also rejects but the
     * known-unsupported list gives a precise error.
     */
    private static final Map<String, String> KNOWN_UNSUPPORTED = Map.of(
            "startswith", "startsWith",
            "endswith", "endsWith",
            "lessthanorequal", "lessThanOrEqual",
            "greaterthanorequal", "greaterThanOrEqual",
            "blank", "blank",
            "notblank", "notBlank"
    );

    /**
     * Translate an AG-Grid {@code filterModel} into the downstream
     * advancedFilter payload shape.
     *
     * @param agGridFilter raw filterModel from the controller (may be
     *                     null or empty for default views)
     * @return downstream-shaped {@code {logic: "and", conditions: [...]}}
     *         payload; empty map when no conditions present (so the
     *         normalizer omits the query param entirely)
     * @throws IllegalArgumentException when any entry is malformed,
     *         multi-condition, set-filter, or uses an unsupported op
     */
    public Map<String, Object> translate(Map<String, Object> agGridFilter) {
        if (agGridFilter == null || agGridFilter.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : agGridFilter.entrySet()) {
            String field = entry.getKey();
            Object rawSpec = entry.getValue();
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException(
                        "AG-Grid filterModel entry has blank field");
            }
            if (!(rawSpec instanceof Map<?, ?> specRaw)) {
                throw new IllegalArgumentException(
                        "AG-Grid filterModel entry '" + field
                                + "' must be a JSON object, got "
                                + (rawSpec == null ? "null" : rawSpec.getClass().getSimpleName()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) specRaw;
            conditions.add(translateOne(field, spec));
        }
        if (conditions.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("logic", "and");
        payload.put("conditions", conditions);
        return payload;
    }

    private Map<String, Object> translateOne(String field, Map<String, Object> spec) {
        rejectMultiCondition(field, spec);
        rejectSetFilter(field, spec);

        Object typeObj = spec.get("type");
        if (!(typeObj instanceof String) || ((String) typeObj).isBlank()) {
            throw new IllegalArgumentException(
                    "AG-Grid filter for field '" + field
                            + "' missing required 'type' string");
        }
        String typeLower = ((String) typeObj).toLowerCase();

        if (KNOWN_UNSUPPORTED.containsKey(typeLower)) {
            throw new IllegalArgumentException(
                    "AG-Grid filter type '" + KNOWN_UNSUPPORTED.get(typeLower)
                            + "' (field '" + field + "') is not supported by "
                            + "remote-http reports in PR-D2.1c2 (downstream "
                            + "user-service decodeAdvancedFilter contract does "
                            + "not include this op); use UI text-input filter "
                            + "or wait for c2.5 contract widening");
        }
        String downstreamOp = OP_MAP.get(typeLower);
        if (downstreamOp == null) {
            throw new IllegalArgumentException(
                    "AG-Grid filter type '" + typeObj + "' (field '" + field
                            + "') is unknown; supported types: " + OP_MAP.keySet());
        }

        Object value = spec.get("filter");
        if (value == null) {
            throw new IllegalArgumentException(
                    "AG-Grid filter for field '" + field
                            + "' missing required 'filter' value");
        }

        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("field", field);
        condition.put("op", downstreamOp);
        condition.put("value", value);

        if ("inRange".equals(downstreamOp)) {
            Object filterTo = spec.get("filterTo");
            if (filterTo == null) {
                throw new IllegalArgumentException(
                        "AG-Grid inRange filter for field '" + field
                                + "' missing required 'filterTo' (value2) "
                                + "for range upper bound");
            }
            condition.put("value2", filterTo);
        }
        return condition;
    }

    /**
     * AG-Grid wraps two predicates on the same field as
     * {@code {filterType, operator: "AND"|"OR", condition1, condition2}}.
     * Reject in c2 — downstream user-service decodeAdvancedFilter only
     * supports flat root logic so {@code (A OR B) AND C} would silently
     * degrade. c2.5 contract widening unblocks this path.
     */
    private static void rejectMultiCondition(String field, Map<String, Object> spec) {
        boolean hasOperator = spec.get("operator") instanceof String;
        boolean hasCondition1 = spec.get("condition1") instanceof Map;
        boolean hasCondition2 = spec.get("condition2") instanceof Map;
        boolean hasConditions = spec.get("conditions") instanceof List;
        if (hasOperator || hasCondition1 || hasCondition2 || hasConditions) {
            throw new IllegalArgumentException(
                    "AG-Grid multi-condition filter on field '" + field
                            + "' is not supported by remote-http reports "
                            + "in PR-D2.1c2 (downstream user-service has "
                            + "flat root logic only); use a single condition "
                            + "or wait for c2.5 nested-logic widening");
        }
    }

    /**
     * AG-Grid set-filter (multi-select dropdown) ships
     * {@code {filterType: "set", values: ["A","B","C"]}}. Downstream
     * user-service has no {@code op:"in"} so reject. c2.5 candidate.
     */
    private static void rejectSetFilter(String field, Map<String, Object> spec) {
        Object filterType = spec.get("filterType");
        if (filterType instanceof String s && "set".equalsIgnoreCase(s)) {
            throw new IllegalArgumentException(
                    "AG-Grid set-filter (multi-select) on field '" + field
                            + "' is not supported by remote-http reports "
                            + "in PR-D2.1c2 (downstream user-service has no "
                            + "op:'in'); use single equals filter or wait "
                            + "for c2.5 op:'in' contract widening");
        }
        // Also reject array `values` payload that AG-Grid set-filter
        // sometimes emits without filterType=set declaration.
        if (spec.get("values") instanceof List) {
            throw new IllegalArgumentException(
                    "AG-Grid filter for field '" + field
                            + "' has array 'values' (set-filter shape); "
                            + "not supported by remote-http reports in "
                            + "PR-D2.1c2 (no downstream op:'in')");
        }
    }
}
