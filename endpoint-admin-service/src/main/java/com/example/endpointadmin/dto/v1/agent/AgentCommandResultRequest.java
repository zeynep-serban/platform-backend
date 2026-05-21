package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.CommandResultStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record AgentCommandResultRequest(
        @NotBlank
        @Size(max = 255)
        String claimId,

        Integer attemptNumber,

        @NotNull
        CommandResultStatus status,

        @Size(max = 1024)
        String summary,

        Map<String, Object> details,

        @Size(max = 128)
        String errorCode,

        @Size(max = 2048)
        String errorMessage,

        Integer exitCode,

        Instant startedAt,

        Instant finishedAt
) {
}
