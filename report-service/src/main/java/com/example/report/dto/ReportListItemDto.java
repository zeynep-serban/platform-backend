package com.example.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Catalog list entry returned by {@code GET /api/v1/reports}.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) adds two optional
 * identity fields that preserve legacy URL + favorites + saved-filter +
 * sidebar default state when a static {@code mfe-reporting} module is
 * retired in favor of a dynamic catalog entry:
 * <ul>
 *   <li>{@code routeSegment} — opt-in alias when the web route differs
 *       from the report key.</li>
 *   <li>{@code sharedReportId} — preserves the {@code SharedReportId} the
 *       frontend favorites sanitizer + saved-filter scope binding key off.</li>
 * </ul>
 */
public record ReportListItemDto(
        String key,
        String title,
        String description,
        String category,
        /** CNS-006 R18: report group for deny-default frontend filtering */
        String reportGroup,
        /** PR-D1a: optional alias for the web route segment (defaults to key). */
        // PR-D1a (Codex 019e800b): NON_NULL field-level (not class-level)
        // so the existing nullable `reportGroup` above continues to emit
        // null on wire for static reports without an access config.
        @JsonInclude(JsonInclude.Include.NON_NULL) String routeSegment,
        /** PR-D1a: optional legacy SharedReportId carry for catalog identity preservation. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String sharedReportId
) {
    /**
     * Backward-compatible 5-arg constructor for pre-PR-D1a call sites
     * (notably the custom-report PostgreSQL row mapper in
     * {@code ReportController#listReports}). New fields default to {@code null}.
     */
    public ReportListItemDto(String key, String title, String description,
                             String category, String reportGroup) {
        this(key, title, description, category, reportGroup, null, null);
    }
}
