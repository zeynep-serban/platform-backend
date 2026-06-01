package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointAppControlSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BE — admin API response shape for AG-041 Application Control snapshot
 * (Faz 22.5). Mirrors AG-040 {@code AdminStartupExposureSnapshotResponse}.
 * Stable wire keys preserved so the web UI can rely on the 20-key
 * surface even when nullable evidence is JSON {@code null}.
 */
public record AdminAppControlSnapshotResponse(
        UUID snapshotId,
        UUID deviceId,
        int schemaVersion,
        boolean supported,
        boolean probeComplete,
        boolean wdacQueryable,
        boolean appLockerQueryable,
        String wdacMode,
        Boolean wdacBootEnforcementPresent,
        Integer wdacActiveCipPolicyCount,
        Boolean wdacLegacySipolicyPresent,
        Boolean wdacMultiPolicyMode,
        String appLockerExeRule,
        String appLockerDllRule,
        String appLockerScriptRule,
        String appLockerMsiRule,
        String appLockerAppxRule,
        String appLockerAppIdSvcState,
        String appLockerAppIdSvcStartup,
        Boolean appLockerAppIdSvcPresent,
        int probeDurationMs,
        List<AdminAppControlProbeErrorResponse> probeErrors,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt
) {
    public static AdminAppControlSnapshotResponse from(EndpointAppControlSnapshot s) {
        return new AdminAppControlSnapshotResponse(
                s.getId(),
                s.getDeviceId(),
                s.getSchemaVersion(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getWdacQueryable(),
                s.getAppLockerQueryable(),
                s.getWdacMode(),
                s.getWdacBootEnforcementPresent(),
                s.getWdacActiveCipPolicyCount(),
                s.getWdacLegacySipolicyPresent(),
                s.getWdacMultiPolicyMode(),
                s.getAppLockerExeRule(),
                s.getAppLockerDllRule(),
                s.getAppLockerScriptRule(),
                s.getAppLockerMsiRule(),
                s.getAppLockerAppxRule(),
                s.getAppLockerAppIdSvcState(),
                s.getAppLockerAppIdSvcStartup(),
                s.getAppLockerAppIdSvcPresent(),
                s.getProbeDurationMs(),
                s.getProbeErrors() == null
                        ? List.of()
                        : s.getProbeErrors().stream()
                                .map(AdminAppControlProbeErrorResponse::from)
                                .toList(),
                s.getPayloadHashSha256(),
                s.getCollectedAt(),
                s.getCreatedAt());
    }
}
