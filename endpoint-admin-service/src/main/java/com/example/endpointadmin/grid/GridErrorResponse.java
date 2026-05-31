package com.example.endpointadmin.grid;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured error envelope for the device-grid endpoints (board #1154).
 * Carries a stable machine-readable {@code code} so the frontend can branch
 * on it rather than parsing free text.
 *
 * <p>Codes: {@code INVALID_GRID_FILTER}, {@code INVALID_GRID_SORT},
 * {@code INVALID_ROW_WINDOW} (PR-2a); {@code INVALID_EXPORT_FORMAT},
 * {@code INVALID_EXPORT_MODE}, {@code EXPORT_ROW_LIMIT_EXCEEDED} (PR-2b).
 *
 * <p>{@code limit} is populated ONLY for {@code EXPORT_ROW_LIMIT_EXCEEDED}
 * (the cap the export exceeded) so the UI can render/branch without parsing
 * the message; it is omitted from the JSON for every other code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GridErrorResponse(String code, String message, Integer limit) {

    /** Errors without a numeric limit (the common case). */
    public GridErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
