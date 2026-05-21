package com.example.endpointadmin.security;

import java.time.Instant;

public record DeviceCredentialResult(
        String deviceId,
        String credentialId,
        Instant authenticatedAt
) {
}
