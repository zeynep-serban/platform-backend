package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.service.HotfixPostureSnapshotTruncation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE — full hotfix-posture snapshot response (Faz 22.5, AG-037 query
 * API). Mirrors the AG-036 {@link AdminOutdatedSoftwareSnapshotResponse}
 * whitelist projection.
 *
 * <p>The raw {@code redactedPayload} jsonb is NOT surfaced; the DTO
 * exposes only the scalar columns, the bounded {@code probeErrors[]},
 * the bounded child lists ({@code installedHotfixes[]} +
 * {@code pendingUpdates[]} + {@code pendingByCategory[]}), and the flat
 * {@link AdminHotfixAgentHealthResponse} agent-health projection.
 *
 * <p>{@code installedPossiblyTruncated} / {@code pendingPossiblyTruncated}
 * surface the truncation hints per
 * {@link HotfixPostureSnapshotTruncation}: prefer the agent's
 * authoritative {@code *Truncated} flags, fall back to {@code count
 * >= max} for defence in depth.
 *
 * <p>{@code probeComplete=false} consumers MUST treat the snapshot as
 * "evidence incomplete" (fail-closed) and never render it as
 * "fully patched"; {@code supported=false} renders a "probe not
 * supported on this device" state.
 *
 * <p>{@code payloadHashSha256} is exposed so the web view can show a
 * change-detection fingerprint without re-fetching the previous
 * snapshot.
 */
public record AdminHotfixPostureSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer installedCount,
        Boolean installedTruncated,
        Integer maxInstalled,
        Boolean installedPossiblyTruncated,
        Integer pendingTotalCount,
        Boolean pendingTruncated,
        Integer maxPending,
        Boolean pendingPossiblyTruncated,
        String installedSourceUsed,
        String pendingSourceUsed,
        String healthSourceUsed,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminHotfixInstalledResponse> installedHotfixes,
        List<AdminHotfixPendingResponse> pendingUpdates,
        List<AdminHotfixPendingByCategoryResponse> pendingByCategory,
        AdminHotfixAgentHealthResponse agentHealth,
        List<AdminHotfixProbeErrorResponse> probeErrors) {

    /**
     * Build the response from a managed entity. The caller MUST be
     * inside an open Hibernate session so the LAZY child associations
     * can be walked ({@code spring.jpa.open-in-view=false} means the
     * controller cannot fold lazily outside a transaction). The
     * controller method runs inside a {@code @Transactional(readOnly =
     * true)} boundary.
     */
    public static AdminHotfixPostureSnapshotResponse from(EndpointHotfixPostureSnapshot snapshot) {
        List<AdminHotfixInstalledResponse> installedDtos = new ArrayList<>();
        if (snapshot.getInstalledHotfixes() != null) {
            for (var i : snapshot.getInstalledHotfixes()) {
                installedDtos.add(AdminHotfixInstalledResponse.from(i));
            }
        }
        List<AdminHotfixPendingResponse> pendingDtos = new ArrayList<>();
        if (snapshot.getPendingUpdates() != null) {
            for (var p : snapshot.getPendingUpdates()) {
                pendingDtos.add(AdminHotfixPendingResponse.from(p));
            }
        }
        List<AdminHotfixPendingByCategoryResponse> byCategoryDtos = new ArrayList<>();
        if (snapshot.getPendingByCategory() != null) {
            for (var c : snapshot.getPendingByCategory()) {
                byCategoryDtos.add(AdminHotfixPendingByCategoryResponse.from(c));
            }
        }
        List<AdminHotfixProbeErrorResponse> errorDtos = new ArrayList<>();
        if (snapshot.getProbeErrors() != null) {
            for (Map<String, Object> raw : snapshot.getProbeErrors()) {
                errorDtos.add(AdminHotfixProbeErrorResponse.from(raw));
            }
        }
        return new AdminHotfixPostureSnapshotResponse(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getSourceCommandResultId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getInstalledCount(),
                snapshot.getInstalledTruncated(),
                snapshot.getMaxInstalled(),
                HotfixPostureSnapshotTruncation.isInstalledPossiblyTruncated(snapshot),
                snapshot.getPendingTotalCount(),
                snapshot.getPendingTruncated(),
                snapshot.getMaxPending(),
                HotfixPostureSnapshotTruncation.isPendingPossiblyTruncated(snapshot),
                snapshot.getInstalledSourceUsed(),
                snapshot.getPendingSourceUsed(),
                snapshot.getHealthSourceUsed(),
                snapshot.getProbeDurationMs(),
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt(),
                installedDtos,
                pendingDtos,
                byCategoryDtos,
                AdminHotfixAgentHealthResponse.from(snapshot),
                errorDtos);
    }
}
