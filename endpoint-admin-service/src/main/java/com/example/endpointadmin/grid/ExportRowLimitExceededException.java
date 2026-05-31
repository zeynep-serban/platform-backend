package com.example.endpointadmin.grid;

/**
 * Raised when an export's bounded preflight count exceeds the configured
 * cap (#1154 PR-2b). The export is refused with a {@code 422} +
 * {@link GridErrorResponse} carrying {@link #CODE} — the file is NEVER
 * silently truncated (Codex 019e7e65). The caller narrows the view filter
 * and retries.
 */
public class ExportRowLimitExceededException extends RuntimeException {

    public static final String CODE = "EXPORT_ROW_LIMIT_EXCEEDED";

    private final int limit;

    public ExportRowLimitExceededException(int limit) {
        super("Export exceeds the maximum of " + limit
                + " rows; narrow the filter (Mevcut görünüm) and retry.");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
