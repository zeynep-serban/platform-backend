package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

public class CreateEndpointEnrollmentResponse {

    private final UUID enrollmentId;
    private final String token;
    private final Instant expiresAt;

    public CreateEndpointEnrollmentResponse(UUID enrollmentId, String token, Instant expiresAt) {
        this.enrollmentId = enrollmentId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "CreateEndpointEnrollmentResponse{"
                + "enrollmentId=" + enrollmentId
                + ", token='[REDACTED]'"
                + ", expiresAt=" + expiresAt
                + '}';
    }
}
