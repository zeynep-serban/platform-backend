package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointServicesProbeError;

/**
 * BE — per-probe-error response DTO for AG-039 services snapshot.
 * Wire shape: {@code {code, serviceName?, summary?}}.
 */
public record AdminServicesProbeErrorResponse(
        Integer rowOrdinal,
        String code,
        String serviceName,
        String summary) {

    public static AdminServicesProbeErrorResponse from(EndpointServicesProbeError e) {
        return new AdminServicesProbeErrorResponse(
                e.getRowOrdinal(),
                e.getCode(),
                e.getServiceName(),
                e.getSummary());
    }
}
