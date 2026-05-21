package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CommandType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CreateEndpointCommandRequest(
        @NotNull
        CommandType type,

        @Size(max = 128)
        String idempotencyKey,

        @Size(max = 512)
        String reason,

        Map<String, Object> payload,

        @Min(0)
        @Max(1000)
        Integer priority,

        @Min(1)
        @Max(10)
        Integer maxAttempts,

        Instant visibleAfterAt,

        Instant expiresAt,

        UUID deviceId
) {
}
