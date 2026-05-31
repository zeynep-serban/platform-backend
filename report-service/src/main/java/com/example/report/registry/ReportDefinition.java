package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Runtime model for a report registry entry.
 *
 * <p>Phase 2 Program 1c (2026-05-07) adds {@code contractVersion} and
 * {@code tenantBoundary} fields to the JSON schema for build-time gate
 * enforcement. These fields are NOT part of the runtime record;
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} lets ReportRegistry
 * continue binding 33 migrated JSON files without forcing a record
 * signature break.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) widens the runtime
 * record with three optional top-level fields that the dynamic-report
 * migration sub-chain needs the backend to author / pass through:
 * <ul>
 *   <li>{@code routeSegment} — opt-in alias when the legacy web route
 *       segment differs from the report key (e.g. backend key
 *       {@code hr-compensation-detay}, frontend route {@code hr-compensation}).</li>
 *   <li>{@code sharedReportId} — preserves catalog identity so favorites,
 *       saved filters, export mode, and sidebar default carry through
 *       the migration without losing user-persistent state.</li>
 *   <li>{@code filterDefinitions} — metadata-driven sidebar widget contract
 *       so the dynamic factory can reproduce the static module's filter
 *       shape from JSON.</li>
 * </ul>
 *
 * <p>All three are nullable and emitted with {@link JsonInclude.Include#NON_NULL}.
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
        AccessConfig access,
        // PR-D1a (Codex 019e800b): NON_NULL applied at field-level so
        // pre-existing nullable wire fields above continue to emit `null`
        // for legacy reports. Only the 3 new D1a fields below are absent
        // from the wire when null.
        @JsonInclude(JsonInclude.Include.NON_NULL) String routeSegment,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sharedReportId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FilterDefinition> filterDefinitions
) {
    public ReportDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Report key must not be blank");
        }
        if ((source == null || source.isBlank()) && (sourceQuery == null || sourceQuery.isBlank())) {
            throw new IllegalArgumentException("Report must have either source (table name) or sourceQuery (custom SQL)");
        }
        if (schemaMode == null || schemaMode.isBlank()) {
            schemaMode = "static";
        }
        if (sourceSchema == null || sourceSchema.isBlank()) {
            if (!"yearly".equals(schemaMode) && !"current".equals(schemaMode)) {
                sourceSchema = "dbo";
            }
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Report must have at least one column");
        }
        if (defaultSortDirection == null || defaultSortDirection.isBlank()) {
            defaultSortDirection = "ASC";
        }
        if (filterDefinitions != null) {
            filterDefinitions = filterDefinitions.isEmpty()
                    ? null
                    : List.copyOf(filterDefinitions);
        }
    }

    /**
     * Backward-compatible 14-arg constructor for pre-PR-D1a call sites
     * (e.g. {@code DashboardQueryEngine.java:341}). The 3 new fields
     * default to {@code null}.
     */
    public ReportDefinition(
            String key, String version, String title, String description,
            String category, String source, String sourceSchema, String schemaMode,
            String yearColumn, String sourceQuery, List<ColumnDefinition> columns,
            String defaultSort, String defaultSortDirection, AccessConfig access) {
        this(key, version, title, description, category, source, sourceSchema,
                schemaMode, yearColumn, sourceQuery, columns, defaultSort,
                defaultSortDirection, access, null, null, null);
    }

    public boolean isYearlySchema() {
        return "yearly".equals(schemaMode);
    }

    public boolean hasSourceQuery() {
        return sourceQuery != null && !sourceQuery.isBlank();
    }
}
