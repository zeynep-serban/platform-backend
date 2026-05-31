package com.example.endpointadmin.grid;

/**
 * Fail-closed validation failure raised by {@code DeviceGridQueryBuilder}
 * when a client grid request violates the allowlist / shape contract
 * (board #1154 PR-2a). The controller maps it to a {@code 400} with a
 * {@link GridErrorResponse} carrying the stable {@link #getCode() code}.
 *
 * <p>NEVER carries SQL or bound values in its message — only a description
 * of the contract violation — so it is safe to surface to the client.
 */
public class GridQueryValidationException extends RuntimeException {

    /** Stable machine-readable code (e.g. {@code INVALID_GRID_FILTER}). */
    private final String code;

    public GridQueryValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
