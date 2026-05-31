package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Dynamic option source for a {@link FilterDefinition}.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31). Used when the
 * widget's selectable values are NOT known at registry-author time and
 * must be fetched at runtime:
 * <ul>
 *   <li>{@code STATIC} — inline {@link FilterDefinition#options()} is the source.</li>
 *   <li>{@code ENDPOINT} — frontend GETs {@code endpoint} (full URL or path).</li>
 *   <li>{@code FILTER_VALUES} — frontend delegates to the existing
 *       {@code GET /v1/reports/{key}/filter-values?column=...} endpoint with
 *       {@code column} set to {@code FilterOptionsSource#column}.</li>
 * </ul>
 *
 * @param type     Source kind. REQUIRED.
 * @param endpoint Backend URL/path (required when {@code type == ENDPOINT}).
 * @param column   Backend column name (required when {@code type == FILTER_VALUES}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterOptionsSource(
        FilterOptionsSourceType type,
        String endpoint,
        String column
) {
    public FilterOptionsSource {
        if (type == null) {
            throw new IllegalArgumentException("FilterOptionsSource.type must not be null");
        }
        if (type == FilterOptionsSourceType.ENDPOINT
                && (endpoint == null || endpoint.isBlank())) {
            throw new IllegalArgumentException(
                    "FilterOptionsSource.endpoint is required when type=endpoint");
        }
        if (type == FilterOptionsSourceType.FILTER_VALUES
                && (column == null || column.isBlank())) {
            throw new IllegalArgumentException(
                    "FilterOptionsSource.column is required when type=filter-values");
        }
    }
}
