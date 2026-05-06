package com.example.report.dto;

import com.example.report.registry.ColumnDefinition;
import java.util.List;

/**
 * Metadata returned by {@code GET /api/v1/reports/{key}/metadata}.
 *
 * <p>{@code capabilities} was added in PR-0.1 (reporting platform hardening
 * plan, May 2026) so the frontend can decide whether to expose grouping /
 * pivot UI. Until PR-0.2 lands, every report ships
 * {@code capabilities.serverSideGrouping=false}, matching the stop-gap UX
 * shipped in platform-web PR #271.
 *
 * <p>Older clients that ignore the new field continue to work; the frontend
 * treats absent capabilities as all-false.
 */
public record ReportMetadataDto(
        String key,
        String title,
        String description,
        String category,
        List<ColumnDefinition> columns,
        String defaultSort,
        String defaultSortDirection,
        ReportCapabilitiesDto capabilities
) {}
