package com.example.report.registry;

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
 * @param defaultAggFunc PR-0.2: default aggregation function applied when
 *                       the column appears in {@code valueCols} without
 *                       an explicit {@code aggFunc}. One of
 *                       {@code sum / avg / min / max / count}; null →
 *                       defaults to {@code sum} for numeric columns and
 *                       {@code count} for everything else.
 */
public record ColumnDefinition(
        String field,
        String headerName,
        String type,
        Integer width,
        boolean sensitive,
        boolean groupable,
        boolean aggregatable,
        String defaultAggFunc
) {
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
        if (defaultAggFunc != null) {
            String normalized = defaultAggFunc.trim().toLowerCase();
            if (!normalized.isEmpty()
                    && !normalized.equals("sum")
                    && !normalized.equals("avg")
                    && !normalized.equals("min")
                    && !normalized.equals("max")
                    && !normalized.equals("count")) {
                throw new IllegalArgumentException(
                        "defaultAggFunc must be one of sum/avg/min/max/count, got: "
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
                false, false, null);
    }
}
