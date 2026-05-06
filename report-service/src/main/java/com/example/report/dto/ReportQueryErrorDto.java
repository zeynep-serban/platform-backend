package com.example.report.dto;

/**
 * Structured error response returned by {@code POST /api/v1/reports/{key}/query}
 * when the request is malformed or asks for capabilities that are not yet
 * enabled.
 *
 * <p>Wire shape:
 * <pre>{@code
 * { "code": "GROUPING_NOT_SUPPORTED", "message": "..." }
 * }</pre>
 *
 * <p>Codes (PR-0.1):
 * <ul>
 *   <li>{@code GROUPING_NOT_SUPPORTED} — payload requests row grouping or
 *       pivot but the report has {@code capabilities.serverSideGrouping=false}.</li>
 *   <li>{@code INVALID_ROW_WINDOW} — {@code endRow <= startRow}.</li>
 *   <li>{@code NON_ALIGNED_ROW_WINDOW} — {@code startRow} is not a multiple
 *       of the derived page size; AG Grid SSRM cache windows must align so
 *       the OFFSET produced by {@link com.example.report.query.SqlBuilder}
 *       matches the requested {@code startRow}.</li>
 * </ul>
 *
 * <p>Frontend can branch on {@code body.code} to surface a deterministic
 * message; the human-readable {@code message} is only for logs / dev
 * tooling.
 */
public record ReportQueryErrorDto(String code, String message) {}
