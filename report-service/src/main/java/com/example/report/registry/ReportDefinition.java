package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Runtime model for a report registry entry.
 *
 * <p>Phase 2 Program 1c (2026-05-07) adds {@code contractVersion} and
 * {@code tenantBoundary} fields to the JSON schema for build-time gate
 * enforcement. These fields are NOT part of the runtime record (no behavior
 * change at this layer); {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * lets ReportRegistry continue binding 31+ migrated JSON files without
 * forcing a record signature break.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportDefinition(
        String key,
        String version,
        String title,
        String description,
        String category,
        String source,
        String sourceSchema,
        String schemaMode,
        String yearColumn,
        String sourceQuery,
        List<ColumnDefinition> columns,
        String defaultSort,
        String defaultSortDirection,
        AccessConfig access
) {
    public ReportDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Report key must not be blank");
        }
        if ((source == null || source.isBlank()) && (sourceQuery == null || sourceQuery.isBlank())) {
            throw new IllegalArgumentException("Report must have either source (table name) or sourceQuery (custom SQL)");
        }
        if (sourceSchema == null || sourceSchema.isBlank()) {
            sourceSchema = "dbo";
        }
        if (schemaMode == null || schemaMode.isBlank()) {
            schemaMode = "static";
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Report must have at least one column");
        }
        if (defaultSortDirection == null || defaultSortDirection.isBlank()) {
            defaultSortDirection = "ASC";
        }
    }

    public boolean isYearlySchema() {
        return "yearly".equals(schemaMode);
    }

    public boolean hasSourceQuery() {
        return sourceQuery != null && !sourceQuery.isBlank();
    }
}
