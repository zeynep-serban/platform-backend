package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) widens the column
 * variant vocabulary so {@code GET /api/v1/reports/{key}/metadata}
 * can return rich rendering hints the 7 static {@code mfe-reporting}
 * modules currently hardcode in TS. The new variants are
 * {@code badge}, {@code status}, {@code currency}, {@code boolean},
 * {@code bold-text}. The new per-variant config fields are
 * {@code variantMap}, {@code labelMap}, {@code statusMap},
 * {@code currencyCode}, {@code decimals}, {@code suffix}, {@code format},
 * {@code defaultVariant}, {@code filterValues}. All new fields are
 * optional and annotated with {@link JsonInclude.Include#NON_NULL} at
 * the field level (NOT the class level) so legacy {@code text/number/date}
 * columns continue to emit identical wire output for the pre-D1a
 * nullable fields ({@code defaultAggFunc}, {@code defaultAggParams},
 * {@code pivotValues}). See {@code docs/architecture/dynamic-report-
 * migration-d0.md §1 / §10} in the platform-web repo for the contract
 * layer mapping.
 *
 * <p>The {@code type} field is now fail-closed: blank/null falls back to
 * {@code "text"} for backward-compatibility with pre-PR-D1a JSON registry
 * entries, but any non-blank unknown value rejects at construction time
 * (and at schema validation time). Whitelist as of PR-D1a:
 * {@code text, number, date, badge, status, currency, boolean, bold-text}.
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
        List<PivotValue> pivotValues,
        // PR-D1a (Codex 019e800b): NON_NULL applied at field-level
        // (not class-level) so pre-existing nullable wire fields above
        // continue to emit `null` for legacy reports. Only the 9 new
        // D1a fields below are absent from the wire when null.
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> variantMap,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> labelMap,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, StatusMapEntry> statusMap,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currencyCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer decimals,
        @JsonInclude(JsonInclude.Include.NON_NULL) String suffix,
        @JsonInclude(JsonInclude.Include.NON_NULL) String format,
        @JsonInclude(JsonInclude.Include.NON_NULL) String defaultVariant,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> filterValues
) {
    /**
     * PR-0.4b (Codex thread {@code 019e2695}): pivot value cardinality cap.
     * Eight concrete buckets is the registry-side fail-closed gate.
     */
    public static final int MAX_PIVOT_VALUES = 8;

    /**
     * PR-D1a: whitelisted {@code type} vocabulary. Blank/null defaults to
     * {@code "text"} for legacy JSON files; any non-blank value outside this
     * set rejects at construction time. {@code link} and {@code actions}
     * variants are intentionally OUT of D-chain scope.
     */
    private static final java.util.Set<String> TYPE_WHITELIST = java.util.Set.of(
            "text", "number", "date", "badge", "status", "currency", "boolean", "bold-text");

    /**
     * PR-D1a: whitelisted {@code format} vocabulary for date columns.
     */
    private static final java.util.Set<String> FORMAT_WHITELIST = java.util.Set.of(
            "short", "long", "datetime", "relative");

    public ColumnDefinition {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Column field must not be blank");
        }
        // PR-D1a fail-closed type validation.
        if (type == null || type.isBlank()) {
            type = "text";
        } else {
            String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
            if (!TYPE_WHITELIST.contains(normalized)) {
                throw new IllegalArgumentException(
                        "Unknown ColumnDefinition.type: " + type
                                + " (whitelist: text/number/date/badge/status/currency/boolean/bold-text)");
            }
            type = normalized;
        }
        if (width == null || width <= 0) {
            width = 150;
        }
        if (defaultAggParams != null) {
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
        if (defaultAggFunc != null) {
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
                    && !normalized.equals("percentilecont")
                    && !normalized.equals("weightedavg")) {
                throw new IllegalArgumentException(
                        "defaultAggFunc must be one of "
                                + "sum/avg/min/max/count/stddev/stddevp/distinctcount/median/percentilecont/weightedavg, got: "
                                + defaultAggFunc);
            }
            defaultAggFunc = normalized.isEmpty() ? null : normalized;
        }
        // PR-D1a: defensive immutable copies + empty → null collapse for new fields.
        if (variantMap != null) {
            variantMap = variantMap.isEmpty() ? null : Map.copyOf(variantMap);
        }
        if (labelMap != null) {
            labelMap = labelMap.isEmpty() ? null : Map.copyOf(labelMap);
        }
        if (statusMap != null) {
            statusMap = statusMap.isEmpty() ? null : Map.copyOf(statusMap);
        }
        if (filterValues != null) {
            filterValues = filterValues.isEmpty() ? null : List.copyOf(filterValues);
        }
        if (format != null) {
            String normalizedFormat = format.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalizedFormat.isEmpty() && !FORMAT_WHITELIST.contains(normalizedFormat)) {
                throw new IllegalArgumentException(
                        "ColumnDefinition.format must be one of short/long/datetime/relative, got: "
                                + format);
            }
            format = normalizedFormat.isEmpty() ? null : normalizedFormat;
        }
        // currencyCode is deliberately NOT defaulted; frontend applies "TRY" when
        // null and type == "currency".
    }

    /**
     * Backward-compatible 5-arg constructor for call sites that predate PR-0.2.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive) {
        this(field, headerName, type, width, sensitive,
                false, false, null, null, false, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible 8-arg constructor for PR-0.2 / PR #6a call sites.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, null, false, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible 9-arg constructor for PR #6b call sites.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc,
                            Map<String, Object> defaultAggParams) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, defaultAggParams, false, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible 10-arg constructor for PR-0.4a call sites.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc,
                            Map<String, Object> defaultAggParams,
                            boolean pivotable) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, defaultAggParams,
                pivotable, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Backward-compatible 11-arg constructor for PR-0.4b call sites
     * that predate PR-D1a's 9 new column-variant config fields.
     */
    public ColumnDefinition(String field, String headerName, String type,
                            Integer width, boolean sensitive,
                            boolean groupable, boolean aggregatable,
                            String defaultAggFunc,
                            Map<String, Object> defaultAggParams,
                            boolean pivotable, List<PivotValue> pivotValues) {
        this(field, headerName, type, width, sensitive,
                groupable, aggregatable, defaultAggFunc, defaultAggParams,
                pivotable, pivotValues,
                null, null, null, null, null, null, null, null, null);
    }
}
