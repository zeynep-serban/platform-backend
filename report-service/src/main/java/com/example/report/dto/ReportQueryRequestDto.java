package com.example.report.dto;

import java.util.List;
import java.util.Map;

/**
 * AG Grid SSRM-compatible request body for {@code POST /api/v1/reports/{key}/query}.
 *
 * <p>Shape matches AG Grid's {@code IServerSideGetRowsRequest} so the
 * frontend can forward its server-side row model request with minimal
 * transformation.
 *
 * <p><b>PR-0.1 contract (this PR)</b> — accepted fields:
 * <ul>
 *   <li>{@code startRow}, {@code endRow}: pagination bounds (translated to
 *       page/pageSize for the existing {@code QueryEngine.executeQuery}).</li>
 *   <li>{@code sortModel}: same shape used by GET /data.</li>
 *   <li>{@code filterModel}: same shape used by GET /data
 *       (advancedFilter parameter).</li>
 * </ul>
 *
 * <p><b>PR-0.1 contract — rejected fields</b> (capability flag is false
 * for every report until PR-0.2). PR-0.1 hardening: {@code valueCols} is
 * fail-closed too — silently ignoring an aggregation request would
 * return flat rows under a "I want sums" payload, which is worse than
 * a clean 400.
 * <ul>
 *   <li>{@code rowGroupCols} non-empty</li>
 *   <li>{@code valueCols} non-empty</li>
 *   <li>{@code pivotMode == true}</li>
 *   <li>{@code pivotCols} non-empty</li>
 *   <li>{@code groupKeys} non-empty</li>
 * </ul>
 * Sending any of the rejected fields with a non-default value yields
 * HTTP 400 with body {@code {"code":"GROUPING_NOT_SUPPORTED",...}}.
 *
 * <p><b>PR-0.2+</b> will graduate {@code rowGroupCols} + {@code groupKeys} +
 * {@code valueCols} from rejected to fully-supported once the SQL GROUP BY
 * builder + capability metadata lands.
 *
 * @param startRow      Inclusive start index (AG Grid SSRM cache window).
 * @param endRow        Exclusive end index.
 * @param rowGroupCols  Columns chosen for row-grouping (PR-0.1: must be
 *                      empty when capability false).
 * @param valueCols     Aggregation columns (only relevant when grouping;
 *                      PR-0.1: rejected when non-empty — see the
 *                      "rejected fields" list above).
 * @param pivotCols     Pivot columns (PR-0.1: must be empty).
 * @param pivotMode     Pivot toggle (PR-0.1: must be false).
 * @param groupKeys     Current expansion path; one entry per opened group
 *                      level (PR-0.1: must be empty).
 * @param filterModel   AG Grid filterModel (same shape as advancedFilter
 *                      query param on GET /data).
 * @param sortModel     AG Grid sortModel (same shape as sort query param
 *                      on GET /data).
 */
public record ReportQueryRequestDto(
        Integer startRow,
        Integer endRow,
        List<ColumnVO> rowGroupCols,
        List<ColumnVO> valueCols,
        List<ColumnVO> pivotCols,
        Boolean pivotMode,
        List<String> groupKeys,
        Map<String, Object> filterModel,
        List<Map<String, String>> sortModel) {

    /**
     * True if the request asks for any grouping / pivot / aggregation
     * behavior. PR-0.1 fails closed on every flavor of grouping intent
     * because the SQL builder doesn't yet emit GROUP BY.
     */
    public boolean requestsGrouping() {
        return (rowGroupCols != null && !rowGroupCols.isEmpty())
                || (valueCols != null && !valueCols.isEmpty())
                || (pivotCols != null && !pivotCols.isEmpty())
                || Boolean.TRUE.equals(pivotMode)
                || (groupKeys != null && !groupKeys.isEmpty());
    }
}
