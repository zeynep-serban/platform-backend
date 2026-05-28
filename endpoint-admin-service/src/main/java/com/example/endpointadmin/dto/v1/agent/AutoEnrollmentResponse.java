package com.example.endpointadmin.dto.v1.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 22.3 — Response of mTLS auto-enroll. {@code status} is {@code "enrolled"}
 * for a freshly created device + cert (HTTP 201) and {@code "already-enrolled"}
 * for an idempotent repeat of an existing active cert (HTTP 200).
 */
public record AutoEnrollmentResponse(
        UUID deviceId,
        String status,
        Instant enrolledAt,
        CertInfo certInfo
) {

    public record CertInfo(
            String sanUri,
            UUID objectGuid,
            String thumbprint,
            Instant notAfter
    ) {
    }
}
