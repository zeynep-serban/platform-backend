package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EndpointHeartbeatRepository extends JpaRepository<EndpointHeartbeat, UUID> {

    List<EndpointHeartbeat> findTop20ByDevice_IdOrderByReceivedAtDesc(UUID deviceId);
}
