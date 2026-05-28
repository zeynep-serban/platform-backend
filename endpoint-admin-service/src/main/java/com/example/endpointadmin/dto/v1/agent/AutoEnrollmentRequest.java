package com.example.endpointadmin.dto.v1.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Faz 22.3 — Body of {@code POST /api/v1/endpoint-agent/endpoint-enrollments/auto}.
 *
 * <p>Authn is mTLS at the TLS layer (the client cert carries the SAN URI
 * device identity). The body carries only secondary identity/inventory
 * fields. Hostname is NOT trusted as identity — it is informational.
 */
public record AutoEnrollmentRequest(
        @NotBlank
        @Size(max = 512)
        String machineFingerprint,

        @NotBlank
        @Size(max = 255)
        String hostname,

        @NotBlank
        @Size(max = 64)
        String osName,

        @Size(max = 255)
        String osVersion,

        @Size(max = 64)
        String osBuild,

        @Size(max = 255)
        String domain,

        @Size(max = 64)
        String architecture,

        @NotBlank
        @Size(max = 128)
        String agentVersion,

        @Min(1)
        Integer schemaVersion
) {
}
