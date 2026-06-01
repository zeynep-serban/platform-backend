package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDiagnosticsProbeError;

/**
 * BE — per-probe-error response DTO for AG-038 diagnostics snapshot.
 * Mirrors the wire shape: {@code {code, summary?}}.
 */
public record AdminDiagnosticsProbeErrorResponse(
        Integer rowOrdinal,
        String code,
        String summary) {

    public static AdminDiagnosticsProbeErrorResponse from(EndpointDiagnosticsProbeError e) {
        return new AdminDiagnosticsProbeErrorResponse(
                e.getRowOrdinal(),
                e.getCode(),
                e.getSummary());
    }
}
