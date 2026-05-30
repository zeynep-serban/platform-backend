package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE — full outdated-software snapshot response (Faz 22.5, AG-036 query
 * API). Mirrors the AG-033 {@code AdminDeviceHealthSnapshotResponse}
 * whitelist projection.
 *
 * <p>The raw {@code redactedPayload} jsonb is NOT surfaced; the DTO exposes
 * only the scalar columns, the bounded {@code probeErrors[]}, and the child
 * {@code packages[]} list (each carrying exactly the contract package keys).
 *
 * <p>{@code possiblyTruncated} surfaces the v1 limitation signal:
 * {@code upgradeCount == maxUpgrade (512)} means the host may have more than
 * 512 pending upgrades that the agent parser capped before
 * {@code upgradeTruncated} was evaluated. Consumers should render a "possibly
 * truncated" hint when this is true.
 *
 * <p>{@code probeComplete=false} consumers MUST treat the snapshot as
 * "evidence incomplete" (fail-closed) and never render it as "fully up to
 * date"; {@code supported=false} renders a "probe not supported on this
 * device" state.
 *
 * <p>{@code payloadHashSha256} is exposed so the web view can show a
 * change-detection fingerprint without re-fetching the previous snapshot.
 */
public record AdminOutdatedSoftwareSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer upgradeCount,
        Boolean upgradeTruncated,
        Integer maxUpgrade,
        Boolean possiblyTruncated,
        String sourceUsed,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminOutdatedSoftwarePackageResponse> packages,
        List<AdminOutdatedSoftwareProbeErrorResponse> probeErrors) {

    /**
     * Build the response from a managed entity. The caller MUST be inside an
     * open Hibernate session so the LAZY {@code packages} association can be
     * walked ({@code spring.jpa.open-in-view=false} means the controller
     * cannot fold lazily outside a transaction). The controller method runs
     * inside a {@code @Transactional(readOnly = true)} boundary.
     */
    public static AdminOutdatedSoftwareSnapshotResponse from(EndpointOutdatedSoftwareSnapshot snapshot) {
        List<AdminOutdatedSoftwarePackageResponse> pkgDtos = new ArrayList<>();
        if (snapshot.getPackages() != null) {
            for (var pkg : snapshot.getPackages()) {
                pkgDtos.add(AdminOutdatedSoftwarePackageResponse.from(pkg));
            }
        }
        List<AdminOutdatedSoftwareProbeErrorResponse> errorDtos = new ArrayList<>();
        if (snapshot.getProbeErrors() != null) {
            for (Map<String, Object> raw : snapshot.getProbeErrors()) {
                errorDtos.add(AdminOutdatedSoftwareProbeErrorResponse.from(raw));
            }
        }
        boolean possiblyTruncated = snapshot.getUpgradeCount() != null
                && snapshot.getMaxUpgrade() != null
                && snapshot.getUpgradeCount().equals(snapshot.getMaxUpgrade());
        return new AdminOutdatedSoftwareSnapshotResponse(
                snapshot.getId(),
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getSourceCommandResultId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getUpgradeCount(),
                snapshot.getUpgradeTruncated(),
                snapshot.getMaxUpgrade(),
                possiblyTruncated,
                snapshot.getSourceUsed(),
                snapshot.getProbeDurationMs(),
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt(),
                pkgDtos,
                errorDtos);
    }
}
