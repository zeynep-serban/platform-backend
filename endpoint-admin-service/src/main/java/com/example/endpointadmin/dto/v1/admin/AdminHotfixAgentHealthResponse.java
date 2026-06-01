package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;

import java.time.Instant;

/**
 * BE — Windows Update agent health projection of a hotfix-posture
 * snapshot response (Faz 22.5, AG-037 query API). Mirrors the wire
 * contract §16.5 agentHealth shape EXACTLY (bounded scalar fields only,
 * no raw registry blob).
 *
 * <p>{@code wuaServiceState}/{@code bitsServiceState}: enum
 * {@code RUNNING|STOPPED|DISABLED|UNKNOWN}.
 *
 * <p>{@code autoUpdatePolicyEnabled}/{@code autoUpdateEffectiveEnabled}:
 * 3-state {@code true|false|null} (null = registry path unreadable).
 *
 * <p>{@code notificationLevel}: AUOptions registry value verbatim
 * (bounded {@code ~ '^[0-9]{1,4}$'}); empty registry value is normalized
 * to {@code null} by the ingest policy.
 */
public record AdminHotfixAgentHealthResponse(
        String wuaServiceState,
        String bitsServiceState,
        Instant lastDetectAt,
        Instant lastInstallAt,
        Boolean autoUpdatePolicyEnabled,
        Boolean autoUpdateEffectiveEnabled,
        String notificationLevel) {

    public static AdminHotfixAgentHealthResponse from(EndpointHotfixPostureSnapshot snapshot) {
        return new AdminHotfixAgentHealthResponse(
                snapshot.getWuaServiceState(),
                snapshot.getBitsServiceState(),
                snapshot.getLastDetectAt(),
                snapshot.getLastInstallAt(),
                snapshot.getAutoUpdatePolicyEnabled(),
                snapshot.getAutoUpdateEffectiveEnabled(),
                snapshot.getNotificationLevel());
    }
}
