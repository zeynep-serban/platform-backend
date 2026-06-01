package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointServicesSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BE — full services snapshot response (Faz 22.5, AG-039-be query API).
 * Mirrors AG-038-be {@code AdminDiagnosticsSnapshotResponse} whitelist
 * projection.
 */
public record AdminServicesSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Integer schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminServiceEntryResponse> services,
        List<AdminServicesProbeErrorResponse> probeErrors) {

    public static AdminServicesSnapshotResponse from(EndpointServicesSnapshot s) {
        List<AdminServiceEntryResponse> entries = new ArrayList<>();
        if (s.getServices() != null) {
            for (var e : s.getServices()) {
                entries.add(AdminServiceEntryResponse.from(e));
            }
        }
        List<AdminServicesProbeErrorResponse> errors = new ArrayList<>();
        if (s.getProbeErrors() != null) {
            for (var e : s.getProbeErrors()) {
                errors.add(AdminServicesProbeErrorResponse.from(e));
            }
        }
        return new AdminServicesSnapshotResponse(
                s.getId(),
                s.getTenantId(),
                s.getDeviceId(),
                s.getSourceCommandResultId(),
                s.getSchemaVersion(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getProbeDurationMs(),
                s.getPayloadHashSha256(),
                s.getCollectedAt(),
                s.getCreatedAt(),
                entries,
                errors);
    }
}
