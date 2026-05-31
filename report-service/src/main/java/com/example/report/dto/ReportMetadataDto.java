package com.example.report.dto;

import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.FilterDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Metadata returned by {@code GET /api/v1/reports/{key}/metadata}.
 *
 * <p>{@code capabilities} was added in PR-0.1 so the frontend can decide
 * whether to expose grouping / pivot UI.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) adds
 * {@code filterDefinitions} so the dynamic-report factory can reproduce
 * the static module's sidebar filter UX from backend JSON metadata.
 */
public record ReportMetadataDto(
        String key,
        String title,
        String description,
        String category,
        List<ColumnDefinition> columns,
        String defaultSort,
        String defaultSortDirection,
        ReportCapabilitiesDto capabilities,
        /** PR-D1a: metadata-driven sidebar filter widget contract. */
        // PR-D1a (Codex 019e800b): NON_NULL field-level so existing
        // nullable fields (description, defaultSort, etc.) continue to
        // emit `null` for legacy reports. Only filterDefinitions is
        // absent from the wire when null.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FilterDefinition> filterDefinitions
) {
    /**
     * Backward-compatible 8-arg constructor for pre-PR-D1a call sites.
     */
    public ReportMetadataDto(
            String key, String title, String description, String category,
            List<ColumnDefinition> columns, String defaultSort,
            String defaultSortDirection, ReportCapabilitiesDto capabilities) {
        this(key, title, description, category, columns, defaultSort,
                defaultSortDirection, capabilities, null);
    }
}
