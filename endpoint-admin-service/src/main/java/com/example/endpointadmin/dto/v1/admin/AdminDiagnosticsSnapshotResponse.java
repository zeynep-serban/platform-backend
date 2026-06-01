package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BE — full diagnostics snapshot response (Faz 22.5, AG-038-be query API).
 * Mirrors the AG-037 {@link AdminHotfixPostureSnapshotResponse} whitelist
 * projection. Scalars + flat lastError triad + bounded probeErrors[].
 *
 * <p>{@code probeComplete=false} consumers MUST treat the snapshot as
 * "agent self-check incomplete" (fail-closed) and never render it as
 * "agent healthy"; {@code supported=false} renders the "diagnostics not
 * supported on this device" state (non-Windows runtime).
 */
public record AdminDiagnosticsSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Integer schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        String agentVersion,
        String configHash,
        Integer lastPollLatencyMs,
        Boolean backendDnsReachable,
        Boolean backendTlsValid,
        AdminDiagnosticsLastErrorResponse lastError,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminDiagnosticsProbeErrorResponse> probeErrors) {

    public static AdminDiagnosticsSnapshotResponse from(EndpointDiagnosticsSnapshot s) {
        List<AdminDiagnosticsProbeErrorResponse> errorDtos = new ArrayList<>();
        if (s.getProbeErrors() != null) {
            for (var e : s.getProbeErrors()) {
                errorDtos.add(AdminDiagnosticsProbeErrorResponse.from(e));
            }
        }
        return new AdminDiagnosticsSnapshotResponse(
                s.getId(),
                s.getTenantId(),
                s.getDeviceId(),
                s.getSourceCommandResultId(),
                s.getSchemaVersion(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getAgentVersion(),
                s.getConfigHash(),
                s.getLastPollLatencyMs(),
                s.getBackendDnsReachable(),
                s.getBackendTlsValid(),
                AdminDiagnosticsLastErrorResponse.from(s),
                s.getProbeDurationMs(),
                s.getPayloadHashSha256(),
                s.getCollectedAt(),
                s.getCreatedAt(),
                errorDtos);
    }
}
