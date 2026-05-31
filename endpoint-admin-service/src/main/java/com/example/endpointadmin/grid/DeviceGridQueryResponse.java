package com.example.endpointadmin.grid;

import java.util.List;
import java.util.Map;

/**
 * AG Grid SSRM block response (board #1154 PR-2a).
 *
 * <p>Each row is a {@code colId -> value} map whose keys are exactly the
 * {@link DeviceGridColumns} ids. Snapshot columns are {@code null} when the
 * device has no device-health / outdated-software snapshot — an
 * authoritative "no snapshot", NOT a "not fetched yet" (the LATERAL join
 * is per-device and unbounded by any cap).
 *
 * @param rows    the page of device rows (size {@code endRow - startRow},
 *                or fewer on the last block)
 * @param lastRow the total row count when this block is the last one, or
 *                {@code -1} when more rows remain (AG Grid SSRM contract)
 */
public record DeviceGridQueryResponse(
        List<Map<String, Object>> rows,
        long lastRow) {
}
