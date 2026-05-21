package com.example.endpointadmin.dto.v1;

import java.time.Instant;

public record EndpointAgentServiceStatusDto(
        String service,
        String status,
        String apiVersion,
        String deviceCredentialProvider,
        Instant timestamp
) {
}
