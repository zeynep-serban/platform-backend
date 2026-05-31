package com.example.endpointadmin.grid;

import java.util.List;
import java.util.Map;

/**
 * AG Grid Server-Side Row Model (SSRM) block request for the endpoint
 * device grid (board #1154 PR-2a).
 *
 * @param startRow        first row index (inclusive, 0-based)
 * @param endRow          last row index (exclusive)
 * @param filterModel     AG Grid filterModel: {@code colId -> filter spec};
 *                        validated fail-closed against
 *                        {@link DeviceGridColumns}
 * @param sortModel       AG Grid sortModel: ordered {@code [{colId, sort}]}
 * @param quickFilterText global quick-filter text (scans the
 *                        quick-filterable text columns)
 */
public record DeviceGridQueryRequest(
        Integer startRow,
        Integer endRow,
        Map<String, Object> filterModel,
        List<Map<String, Object>> sortModel,
        String quickFilterText) {
}
