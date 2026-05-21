package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.CommandType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentCommandResponse(
        UUID commandId,
        String claimId,
        int attemptNumber,
        CommandType type,
        String requestedBy,
        String reason,
        Map<String, Object> payload,
        Instant claimExpiresAt
) {
}
