package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;

import java.time.Instant;
import java.util.UUID;

public record AdminAgentUpdateReleaseSummary(
        UUID id,
        String releaseId,
        AgentUpdateChannel channel,
        String targetVersion,
        AgentUpdateSigningTier signingTier,
        AgentUpdateReleaseStatus status,
        boolean enabled,
        Instant lastUpdatedAt
) {

    public static AdminAgentUpdateReleaseSummary from(
            EndpointAgentUpdateRelease release) {
        return new AdminAgentUpdateReleaseSummary(
                release.getId(),
                release.getReleaseId(),
                release.getChannel(),
                release.getTargetVersion(),
                release.getSigningTier(),
                release.getStatus(),
                release.isEnabled(),
                release.getLastUpdatedAt()
        );
    }
}
