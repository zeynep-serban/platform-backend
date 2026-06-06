package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointAgentUpdateReleaseRepository
        extends JpaRepository<EndpointAgentUpdateRelease, UUID> {

    Optional<EndpointAgentUpdateRelease>
        findByTenantIdAndReleaseId(UUID tenantId, String releaseId);

    Page<EndpointAgentUpdateRelease>
        findByTenantId(UUID tenantId, Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndStatus(UUID tenantId,
                                AgentUpdateReleaseStatus status,
                                Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndEnabled(UUID tenantId,
                                 boolean enabled,
                                 Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndStatusAndEnabled(UUID tenantId,
                                          AgentUpdateReleaseStatus status,
                                          boolean enabled,
                                          Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndChannel(UUID tenantId,
                                 AgentUpdateChannel channel,
                                 Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndChannelAndEnabled(UUID tenantId,
                                           AgentUpdateChannel channel,
                                           boolean enabled,
                                           Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndChannelAndStatus(UUID tenantId,
                                          AgentUpdateChannel channel,
                                          AgentUpdateReleaseStatus status,
                                          Pageable pageable);

    Page<EndpointAgentUpdateRelease>
        findByTenantIdAndChannelAndStatusAndEnabled(
                UUID tenantId,
                AgentUpdateChannel channel,
                AgentUpdateReleaseStatus status,
                boolean enabled,
                Pageable pageable);

    boolean existsByTenantIdAndReleaseId(UUID tenantId, String releaseId);
}
