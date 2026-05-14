package com.example.report.registry;

import java.util.List;
import java.util.Map;

/**
 * Per-column metadata declared in a report's JSON registry entry.
 *
 * <p>PR-0.2 (reporting platform hardening, 2026-05) adds three optional
 * grouping/aggregation hints — {@code groupable}, {@code aggregatable},
 * {@code defaultAggFunc}. Each is opt-in per column so reports can light
 * up SSRM grouping incrementally without forcing it across the catalog.
 *
 * @param field          SQL column name; must match the source query.
 * @param headerName     User-facing header.
 * @param type           Display type (text, number, date).
 * @param width          Default column width in pixels.
 * @param sensitive      When true, column is masked unless the user holds
 *                       the report's sensitive-column grant.
 * @param groupable      PR-0.2: when true, AG Grid SSRM may use this
 *                       column as a row-group dimension. Reports that
 *                       have at least one {@code groupable=true} column
 *                       advertise {@code capabilities.serverSideGrouping=true}
 *                       in the metadata response.
 * @param aggregatable   PR-0.2: when true, the column is offered as a
 *                       {@code valueCols} candidate (frontend value-column
 *                       picker).
 * @param defaultAggFunc      PR-0.2: default aggregation function applied
 *                            when the column appears in {@code valueCols}
 *                            without an explicit {@code aggFunc}. One of
 *                            {@code sum / avg / min / max / count / stddev /
 *                            stddevp / distinctcount / median /
 *                            percentilecont} (PR-0.4z + PR #6a + PR #6b
 *                            extended); null → defaults to {@code sum} for
 *                            numeric columns and {@code count} for
 *                            everything else. {@code weightedAvg} (PR-0.4
 *                            with value+weight pair semantics) remains on
 *                            the roadmap.
 * @param defaultAggParams    PR #6b: parametric aggregation defaults. For
 *                            {@code percentilecont} the map must carry a
 *                            {@code percentile} entry whose value is a
 *                            {@link Number} in {@code [0, 1]}. The
 *                            controller layer ({@code sanitizeAggParams})
 *                            only consults this map when the resolved
 *                            aggregation func actually matches the
 *                            registry's default func, so a stray
 *                            percentile default cannot leak into an
 *                            explicit {@code sum} override.
 */
public record ColumnDefinition(
        String field,
        String headerName,
        String type,
        Integer width,
        boolean sensitive,
        boolean groupable,
        boolean aggregatable,
        String defaultAggFunc,
        Map<String, Object> defaultAggParams,
        boolean pivotable,
        List<PivotValue> pivotValues
) {
    /**
     * PR-0.4b (Codex thread {@code 019e2695}): pivot value cardinality cap.
     * Eight concrete buckets is the registry-side fail-closed gate; combined
     * with the {@code pivotValues * valueCols <= 32} controller-side cap it
     * keeps the {@code CASE WHEN} aggregate column count bounded so a
     * single report cannot blow past AG Grid's usable column budget.
     */
    public static final int MAX_PIVOT_VALUES = 8;

    public ColumnDefinition {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Column field must not be blank");
        }
        if (type == null || type.isBlank()) {
            type = "text";
        }
        if (width == null || width <= 0) {
            width = 150;
        }
        if (defaultAggParams != null) {
            // Defensive: take an immutable copy so the registry-loaded
            // map cannot be mutated underneath us after canonical
            // construction. Empty maps collapse to null for clarity.
            defaultAggParams = defaultAggParams.isEmpty()
                    ? null
                    : Map.copyOf(defaultAggParams);
        }
        if (pivotValues != null) {
            if (pivotValues.isEmpty()) {
                pivotValues = null;
            } else {
                if (pivotValues.size() > MAX_PIVOT_VALUES) {
                    throw new IllegalArgumentException(
                            "pivotValues size " + pivotValues.size()
                                    + " exceeds the per-column cap of "
                                    + MAX_PIVOT_VALUES);
                }
                // Reject duplicate SQL values so the alias contract stays
                // injective; two registry entries that collapse to the
                // same `[field] = :value` predicate would produce
                // duplicate response columns AG Grid cannot disambiguate.
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (PivotValue pv : pivotValues) {
                    if (pv == null) {
                        throw new IllegalArgumentException(
                                "pivotValues entry must not be null");
                    }
                    if (!seen.add(pv.value())) {
                        throw new IllegalArgumentException(
                                "pivotValues entries must have unique value, "
                                        + "duplicate: " + pv.value());
                    }
                }
                pivotValues = List.copyOf(pivotValues);
            }
        }
        // PR-0.4b registry-time note: `pivotable=true` without
        // `pivotValues` is preserved as-is at the record layer so PR-0.4a
        // JSON entries that pre-date the {@code pivotValues} field keep
        // deserialising. The controller-side pivot dispatcher
        // ({@code multiLevelPivotRequest} + {@code sanitizePivotRequest})
        // is the authoritative gate: a request that targets a pivotable
        // column without an enumerated {@code pivotValues} list fails
        // closed with {@code PIVOT_NOT_CONFIGURED} rather than letting
        // dynamic {@code SELECT DISTINCT} discovery sneak in.
        if (defaultAggFunc != null) {
            // Locale.ROOT keeps the Turkish dotless-ı pitfall out of the
            // canonical comparison — "MEDIAN".toLowerCase() under tr_TR
            // would otherwise return "medıan" and miss the whitelist
            // entry. Same defensive normalisation pattern as
            // GroupedAggregation on the SqlBuilder side.
            String normalized = defaultAggFunc.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalized.isEmpty()
                    && !normalized.equals("sum")
                    && !normalized.equals("avg")
                    && !normalized.equals("min")
                    && !normalized.equals("max")
                    && !normalized.equals("count")
                    && !normalized.equals("stddev")
                    && !normalized.equals("stddevp")
                    && !normalized.equals("distinctcount")
                    && !normalized.equals("median")
                    && !normalized.equals("percentilecont")) {
                throw new IllegalArgumentException(
                        "defaultAggFunc must be one of "
                                + "sum/avg/min/max/count/stddev/stddevp/distinctcount/median/percentilecont, got: "
                                + defaultAggFunc);
            }
            defaultAggFunc = normalized.isEmpty() ? null : normalized;
        }
    }

    /**
     * Backward-compatible 5-arg constructor for call sites that predate
     * PR-0.2. Defaults the new grouping/aggregation flags to {@code false}
     * and {@code null} so existing tests / JSON registry entries continue
     * to deserialize without modification.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive) {
        this(field, headerName, type, width, sensitive,
                false, false, null, null, false, null);
    }

    /**
     * Backward-compatible 8-arg constructor for PR-0.2 / PR #6a call
     * sites that predate PR #6b's {@code defaultAggParams} field.
     * Defaults the new map to {@code null} so existing tests and
     * registry entries continue to deserialize without modification.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, null, false, null);
    }

    /**
     * Backward-compatible 9-arg constructor for PR #6b call sites that
     * predate PR-0.4a's {@code pivotable} flag. Defaults the new flag
     * to {@code false} so existing tests / registry entries continue
     * to deserialize without modification.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc,
                            Map<String, Object> defaultAggParams) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, defaultAggParams, false, null);
    }

    /**
     * Backward-compatible 10-arg constructor for PR-0.4a call sites
     * that predate PR-0.4b's {@code pivotValues} field. Defaults the
     * new list to {@code null} so existing tests and registry entries
     * continue to deserialize without modification. Note that this
     * shortcut path bypasses the {@code pivotable=true → pivotValues
     * required} invariant: callers that hand-build a pivotable column
     * via the 10-arg path must follow up with the 11-arg canonical
     * once they have real values, otherwise the controller will
     * reject pivot requests at runtime.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc,
                            Map<String, Object> defaultAggParams,
                            boolean pivotable) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, defaultAggParams,
                pivotable, null);
    }
}
