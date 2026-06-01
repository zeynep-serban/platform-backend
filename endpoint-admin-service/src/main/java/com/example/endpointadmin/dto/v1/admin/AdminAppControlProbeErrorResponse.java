package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointAppControlProbeError;

/**
 * BE — admin DTO for AG-041 Application Control probe-error row.
 * Mirrors AG-040 {@code AdminStartupExposureProbeErrorResponse}.
 */
public record AdminAppControlProbeErrorResponse(
        int rowOrdinal,
        String code,
        String source,
        String summary
) {
    public static AdminAppControlProbeErrorResponse from(EndpointAppControlProbeError e) {
        return new AdminAppControlProbeErrorResponse(
                e.getRowOrdinal(),
                e.getCode(),
                e.getSource(),
                e.getSummary());
    }
}
