package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointHeartbeatRepository extends JpaRepository<EndpointHeartbeat, UUID> {

    List<EndpointHeartbeat> findTop20ByDevice_IdOrderByReceivedAtDesc(UUID deviceId);

    /**
     * AG-028 Phase 1b — return the single most recent heartbeat row for a
     * device. Used by {@code EndpointUninstallService.approve} to evaluate
     * heartbeat freshness AND capability advertisement from the same row
     * (Codex post-impl iter-1 absorb, thread `019e8dcd` must-fix #3).
     *
     * <p>{@code device.lastSeenAt} is updated by both heartbeat AND
     * enrollment/cert-rotate paths, so it is NOT a reliable proxy for "an
     * agent that advertised capabilities recently." Reading the most recent
     * heartbeat row directly ties the freshness check (its
     * {@code receivedAt}) and the capability check (its
     * {@code payload.capabilities}) to the same source of truth.
     */
    Optional<EndpointHeartbeat>
        findFirstByDevice_IdOrderByReceivedAtDesc(UUID deviceId);
}
