package com.example.report.dto;

import java.util.List;
import java.util.Map;

/**
 * PR-0.5b (Codex thread 019e2cd7) — request body for
 * {@code POST /api/v1/reports/{key}/export}. Carries the AG Grid
 * grid-state snapshot so the exported file matches the user's
 * on-screen view (row grouping, value aggregations, pivot, filter,
 * sort).
 *
 * <p>Dispatch rules (mirror {@link ReportQueryRequestDto}'s
 * grouping/pivot classifier on the live query path):
 * <ul>
 *   <li>{@code pivotMode == true && rowGroupCols.size() == 1 &&
 *       pivotCols.size() == 1 && !valueCols.isEmpty()} →
 *       {@code SqlBuilder.buildPivotedGroupedExportQuery}.</li>
 *   <li>{@code rowGroupCols.size() >= 1 && !valueCols.isEmpty() &&
 *       !pivotMode} → {@code SqlBuilder.buildGroupedExportQuery}
 *       (multi-level leaf bucket table).</li>
 *   <li>else → {@code SqlBuilder.buildExportQuery} (flat).</li>
 * </ul>
 *
 * <p>The {@code groupKeys} field is intentionally ignored on the
 * export path — exports surface every bucket, not the user's current
 * expansion frontier. The {@code startRow/endRow} fields are absent
 * too; export is unpaginated up to the configured
 * {@code report.query.max-export-rows} cap.
 *
 * @param format         {@code "csv"} or {@code "xlsx"}; defaults to
 *                       {@code csv} when null/blank.
 * @param rowGroupCols   AG Grid row group columns
 *                       (multi-level supported on the non-pivot path).
 * @param valueCols      AG Grid value (aggregation) columns.
 * @param pivotCols      AG Grid pivot columns (single-column only).
 * @param pivotMode      Pivot toggle.
 * @param filterModel    AG Grid filterModel (advancedFilter).
 * @param sortModel      AG Grid sortModel.
 */
public record ReportExportRequestDto(
        String format,
        List<ColumnVO> rowGroupCols,
        List<ColumnVO> valueCols,
        List<ColumnVO> pivotCols,
        Boolean pivotMode,
        Map<String, Object> filterModel,
        List<Map<String, String>> sortModel) {

    /** Convenience: true iff the request snapshot expresses grouping intent. */
    public boolean requestsGrouping() {
        return (rowGroupCols != null && !rowGroupCols.isEmpty())
                || (valueCols != null && !valueCols.isEmpty())
                || (pivotCols != null && !pivotCols.isEmpty())
                || Boolean.TRUE.equals(pivotMode);
    }

    /** Convenience: true iff the request snapshot expresses pivot intent. */
    public boolean requestsPivot() {
        return Boolean.TRUE.equals(pivotMode)
                && rowGroupCols != null && !rowGroupCols.isEmpty()
                && pivotCols != null && !pivotCols.isEmpty()
                && valueCols != null && !valueCols.isEmpty();
    }
}
