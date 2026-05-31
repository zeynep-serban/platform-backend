package com.example.endpointadmin.grid;

/**
 * Structured error envelope for the device-grid endpoints (board #1154).
 * Carries a stable machine-readable {@code code} so the frontend can branch
 * on it rather than parsing free text.
 *
 * <p>Codes: {@code INVALID_GRID_FILTER}, {@code INVALID_GRID_SORT},
 * {@code INVALID_ROW_WINDOW} (PR-2a); {@code EXPORT_ROW_LIMIT_EXCEEDED}
 * (PR-2b).
 */
public record GridErrorResponse(String code, String message) {
}
