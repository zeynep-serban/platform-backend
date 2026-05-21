package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.OsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentHeartbeatRequest(
        @Size(max = 128)
        String installId,

        @Size(max = 255)
        String hostname,

        OsType osType,

        OsType osFamily,

        @Size(max = 64)
        String architecture,

        @NotBlank
        @Size(max = 128)
        String agentVersion,

        @Size(max = 255)
        String osVersion,

        @NotBlank
        @Size(max = 64)
        String state,

        @Size(max = 128)
        List<@Size(max = 128) String> capabilities,

        Instant timestamp,

        Map<String, Object> inventory,

        List<Map<String, Object>> localUsers,

        Map<String, Object> metrics
) {

    public OsType resolvedOsType() {
        return osType != null ? osType : osFamily;
    }
}
