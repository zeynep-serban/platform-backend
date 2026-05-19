package com.example.report.registry;

import java.util.List;
import java.util.Map;

public record AccessConfig(
        String permission,
        String reportGroup,
        Map<String, List<String>> columnRestrictions,
        RowFilter rowFilter
) {
    public record RowFilter(
            String column,
            String scopeType,
            String bypassPermission,
            SourceQueryBoundary sourceQueryBoundary
    ) {
        /** Backward-compat: rowFilter without an RC-004 v2 sourceQuery boundary. */
        public RowFilter(String column, String scopeType, String bypassPermission) {
            this(column, scopeType, bypassPermission, null);
        }
    }

    /**
     * RC-004 v2 (Codex thread 019e3f5c) — declarative company-boundary
     * projection for a {@code sourceQuery} report whose physical base table
     * has no company column.
     *
     * <p>The base table ({@code sourceTable}) is joined to a company-boundary
     * table ({@code boundaryTable}, e.g. {@code EMPLOYEE_POSITIONS}) on the
     * employee key; the boundary table's company column ({@code boundaryColumn})
     * is projected under {@code projectedColumn}, and the rowFilter scopes on
     * that projected alias. RC-004 cross-checks the {@code sourceQuery} text
     * against this declaration so a COMPANY rowFilter on a {@code sourceQuery}
     * report stays provably tenant-isolated.
     */
    public record SourceQueryBoundary(
            String mode,
            String sourceTable,
            String sourceAlias,
            String sourceJoinColumn,
            String boundaryTable,
            String boundaryAlias,
            String boundaryJoinColumn,
            String boundaryColumn,
            String projectedColumn
    ) {}
}
