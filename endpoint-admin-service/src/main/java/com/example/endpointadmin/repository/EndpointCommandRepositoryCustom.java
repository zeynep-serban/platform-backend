package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointCommand;

import java.time.Instant;
import java.util.List;

public interface EndpointCommandRepositoryCustom {

    List<EndpointCommand> claimDeliverableBatch(String workerId, Instant now, Instant lockedUntil, int batchSize);
}
