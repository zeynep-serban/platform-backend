package com.example.endpointadmin.security;

import java.time.Instant;

public record DeviceCredentialRequest(
        String deviceId,
        String keyId,
        String signature,
        Instant requestTimestamp,
        String nonce,
        String canonicalPayload
) {
}
