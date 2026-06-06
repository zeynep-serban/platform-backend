package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;

import java.time.Instant;
import java.util.UUID;

public record AdminAgentUpdateReleaseResponse(
        UUID id,
        UUID tenantId,
        String releaseId,
        AgentUpdateChannel channel,
        String targetVersion,
        String binaryUrl,
        String manifestUrl,
        String sha256,
        String sha512,
        String signerThumbprint,
        AgentUpdateSigningTier signingTier,
        long maxBytes,
        String releaseNotes,
        AgentUpdateReleaseStatus status,
        boolean enabled,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant lastUpdatedAt,
        String approvedBySubject,
        Instant approvedAt,
        String revokedBySubject,
        Instant revokedAt,
        String revocationReason
) {

    public static AdminAgentUpdateReleaseResponse from(
            EndpointAgentUpdateRelease release) {
        return new AdminAgentUpdateReleaseResponse(
                release.getId(),
                release.getTenantId(),
                release.getReleaseId(),
                release.getChannel(),
                release.getTargetVersion(),
                release.getBinaryUrl(),
                release.getManifestUrl(),
                release.getSha256(),
                release.getSha512(),
                release.getSignerThumbprint(),
                release.getSigningTier(),
                release.getMaxBytes(),
                release.getReleaseNotes(),
                release.getStatus(),
                release.isEnabled(),
                release.getCreatedBySubject(),
                release.getCreatedAt(),
                release.getLastUpdatedBySubject(),
                release.getLastUpdatedAt(),
                release.getApprovedBySubject(),
                release.getApprovedAt(),
                release.getRevokedBySubject(),
                release.getRevokedAt(),
                release.getRevocationReason()
        );
    }
}
