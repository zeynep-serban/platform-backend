package com.example.report.execution;

import java.util.List;
import java.util.Map;

/**
 * Normalized result DTO for the remote-http executor (PR-D2.1c1).
 *
 * <p>The {@link RemoteResponseNormalizer} produces this from the raw
 * downstream JSON response according to the declared
 * {@link com.example.report.registry.ExecutionConfig#responseShape()}.
 *
 * <p>The controller layer then maps this to the standard report
 * {@code ReportDataDto} (rows + total + page + pageSize), keeping the
 * frontend dynamic-report client unchanged.
 *
 * @param rows   Result rows, each row is a column-keyed map (JSON object)
 * @param total  Total result count (across all pages, NOT just current page);
 *               for {@code items-array} shape this equals {@code rows.size()};
 *               for {@code paged-items-total} shape it comes from the
 *               downstream response's {@code total} field
 */
public record RemoteReportResult(
        List<Map<String, Object>> rows,
        long total
) {
    public RemoteReportResult {
        if (rows == null) {
            throw new IllegalArgumentException("RemoteReportResult.rows must not be null");
        }
        rows = List.copyOf(rows);
        if (total < 0) {
            throw new IllegalArgumentException("RemoteReportResult.total must be >= 0: " + total);
        }
    }

    /** Empty result helper for tests / no-op paths. */
    public static RemoteReportResult empty() {
        return new RemoteReportResult(List.of(), 0L);
    }
}
