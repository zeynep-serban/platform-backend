package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;

import java.time.Instant;

/**
 * BE — lastError facet DTO for AG-038 diagnostics snapshot.
 * Flat triad mirroring the wire shape: {@code {occurredAt, code, summary}}.
 * All three fields are present together (V23 triad CHECK) or the whole
 * facet is {@code null}.
 */
public record AdminDiagnosticsLastErrorResponse(
        Instant occurredAt,
        String code,
        String summary) {

    public static AdminDiagnosticsLastErrorResponse from(EndpointDiagnosticsSnapshot s) {
        if (s.getLastErrorOccurredAt() == null
                && s.getLastErrorCode() == null
                && s.getLastErrorSummary() == null) {
            return null;
        }
        return new AdminDiagnosticsLastErrorResponse(
                s.getLastErrorOccurredAt(),
                s.getLastErrorCode(),
                s.getLastErrorSummary());
    }
}
