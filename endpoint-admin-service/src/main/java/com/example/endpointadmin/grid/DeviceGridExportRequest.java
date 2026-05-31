package com.example.endpointadmin.grid;

import java.util.List;
import java.util.Map;

/**
 * Report-style export request for the endpoint-device grid (#1154 PR-2b),
 * mirroring the reporting İndir ▾ dropdown.
 *
 * @param format          {@code csv} or {@code xlsx}
 * @param exportMode      {@code raw} (Ham veri — full tenant dataset, canonical
 *                        columns) or {@code view} (Mevcut görünüm — current
 *                        filter/sort/quick-filter + visible columns)
 * @param filterModel     AG Grid filterModel (VIEW only)
 * @param sortModel       AG Grid sortModel (VIEW only)
 * @param quickFilterText quick-filter text (VIEW only)
 * @param columns         visible column ids for VIEW export; server validates
 *                        each against the allowlist and ignores them for RAW
 */
public record DeviceGridExportRequest(
        String format,
        String exportMode,
        Map<String, Object> filterModel,
        List<Map<String, Object>> sortModel,
        String quickFilterText,
        List<String> columns) {
}
