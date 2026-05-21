package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CommandResultStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EndpointCommandResultDto(
        UUID id,
        CommandResultStatus status,
        Map<String, Object> payload,
        String errorCode,
        String errorMessage,
        Integer exitCode,
        Instant reportedAt,
        Instant createdAt
) {
}
