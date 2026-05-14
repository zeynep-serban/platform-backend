package com.example.report.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public class FilterTranslator {

    /**
     * PR #5a (Codex 019e2695 review iter-3): cap on recursive compound
     * filter nesting depth so a malicious payload cannot exhaust the
     * stack. AG Grid UI typically produces 2-3 nested levels at most;
     * 16 leaves comfortable headroom while protecting the parser.
     * Mirrors the PR-0.3 {@code MAX_ROW_GROUP_DEPTH = 8} discipline
     * on the controller side.
     */
    private static final int MAX_COMPOUND_DEPTH = 16;

    private int paramCounter = 0;

    public record FilterResult(String whereClause, MapSqlParameterSource params) {}

    public FilterResult translate(Map<String, Object> agGridFilter, Set<String> allowedColumns) {
        if (agGridFilter == null || agGridFilter.isEmpty()) {
            return new FilterResult("", new MapSqlParameterSource());
        }

        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        for (Map.Entry<String, Object> entry : agGridFilter.entrySet()) {
            String column = entry.getKey();
            if (!allowedColumns.contains(column)) {
                continue;
            }

            Object filterModel = entry.getValue();
            if (filterModel instanceof Map<?, ?> filterMap) {
                String clause = translateSingleFilter(column, filterMap, params, 0);
                if (clause != null && !clause.isBlank()) {
                    clauses.add(clause);
                }
            }
        }

        String combined = String.join(" AND ", clauses);
        return new FilterResult(combined, params);
    }

    @SuppressWarnings("unchecked")
    private String translateSingleFilter(String column, Map<?, ?> filterMap, MapSqlParameterSource params, int depth) {
        // PR #5a (Codex thread 019e2695): if the filter entry has both
        // an `operator` AND at least one compound child slot
        // (`conditions[]`, `condition1`, or `condition2`), dispatch to
        // the recursive compound parser. The double check is intentional:
        // a simple filter that happens to carry a stray `operator`
        // metadata key (e.g. an AG Grid version that always emits the
        // field) must still hit the simple-filter path below, not
        // disappear into the compound branch.
        if (filterMap.containsKey("operator")
                && (filterMap.containsKey("conditions")
                        || filterMap.containsKey("condition1")
                        || filterMap.containsKey("condition2"))) {
            return translateCompoundFilter(column, filterMap, params, depth);
        }

        String filterType = (String) filterMap.get("filterType");
        String type = (String) filterMap.get("type");

        if ("set".equals(filterType)) {
            List<String> values = (List<String>) filterMap.get("values");
            if (values == null || values.isEmpty()) {
                return null;
            }
            String paramName = nextParam();
            params.addValue(paramName, values);
            return "[" + column + "] IN (:" + paramName + ")";
        }

        if (type == null) {
            return null;
        }

        return switch (type) {
            case "contains" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter") + "%");
                yield "[" + column + "] LIKE :" + p;
            }
            case "notContains" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter") + "%");
                yield "[" + column + "] NOT LIKE :" + p;
            }
            case "equals" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] = :" + p;
            }
            case "notEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] <> :" + p;
            }
            case "startsWith" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter") + "%");
                yield "[" + column + "] LIKE :" + p;
            }
            case "endsWith" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter"));
                yield "[" + column + "] LIKE :" + p;
            }
            case "lessThan" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] < :" + p;
            }
            case "lessThanOrEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] <= :" + p;
            }
            case "greaterThan" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] > :" + p;
            }
            case "greaterThanOrEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] >= :" + p;
            }
            case "inRange" -> {
                String pFrom = nextParam();
                String pTo = nextParam();
                params.addValue(pFrom, filterMap.get("filter"));
                params.addValue(pTo, filterMap.get("filterTo"));
                yield "[" + column + "] BETWEEN :" + pFrom + " AND :" + pTo;
            }
            case "blank" -> "[" + column + "] IS NULL";
            case "notBlank" -> "[" + column + "] IS NOT NULL";
            default -> null;
        };
    }

    private String nextParam() {
        return "p" + (++paramCounter);
    }

    /**
     * PR #5a (Codex thread 019e2695): recursive parser for AG Grid
     * compound filter entries.
     *
     * <p>Two payload shapes are accepted:
     * <ul>
     *   <li>Legacy two-slot: {@code {operator: "AND", condition1: {...},
     *       condition2: {...}}}</li>
     *   <li>Modern array: {@code {operator: "AND", conditions: [...]}}</li>
     * </ul>
     *
     * <p>{@code operator} is case-insensitive and must be {@code AND}
     * or {@code OR}. Anything else (including a missing operator) is
     * dropped to {@code null} — same defensive-skip semantics as the
     * existing simple-filter {@code default ->} branch. The merge
     * upstream ({@link com.example.report.controller.ReportController#mergeAncestorFilters})
     * only emits {@code AND}, but the parser still accepts {@code OR}
     * because user-supplied filterModel entries may carry a native
     * compound {@code OR} (e.g. two text-filter chips connected with
     * an OR). Sub-conditions that fail to translate are dropped
     * silently; the surviving siblings are still joined.
     */
    @SuppressWarnings("unchecked")
    private String translateCompoundFilter(String column, Map<?, ?> filterMap, MapSqlParameterSource params, int depth) {
        // PR #5a (Codex 019e2695 review iter-3): depth cap. AG Grid UI
        // typically produces 2-3 nested levels; anything deeper than
        // MAX_COMPOUND_DEPTH is dropped to null so a request-controlled
        // payload cannot exhaust the JVM stack.
        if (depth >= MAX_COMPOUND_DEPTH) {
            return null;
        }

        Object opObj = filterMap.get("operator");
        if (!(opObj instanceof String opStr)) {
            return null;
        }
        String operator = opStr.trim().toUpperCase();
        if (!"AND".equals(operator) && !"OR".equals(operator)) {
            return null;
        }

        List<Map<?, ?>> childConditions = new ArrayList<>();
        Object conditionsArr = filterMap.get("conditions");
        if (conditionsArr instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    childConditions.add(childMap);
                }
            }
        } else {
            // Legacy two-slot shape — condition1/condition2.
            Object c1 = filterMap.get("condition1");
            Object c2 = filterMap.get("condition2");
            if (c1 instanceof Map<?, ?> c1Map) childConditions.add(c1Map);
            if (c2 instanceof Map<?, ?> c2Map) childConditions.add(c2Map);
        }

        List<String> clauses = new ArrayList<>();
        for (Map<?, ?> child : childConditions) {
            String clause = translateSingleFilter(column, child, params, depth + 1);
            if (clause != null && !clause.isBlank()) {
                clauses.add(clause);
            }
        }

        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            // Single surviving child — no need for an outer wrapper.
            return clauses.get(0);
        }
        return "(" + String.join(" " + operator + " ", clauses) + ")";
    }
}
