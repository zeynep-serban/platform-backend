package com.example.endpointadmin.dto.v1.agent;

import java.time.Instant;
import java.util.UUID;

public class ConsumeEnrollmentResponse {

    private final UUID deviceId;
    private final String credentialKeyId;
    private final String secret;
    private final String hmacAlgorithm;
    private final Instant serverTime;

    public ConsumeEnrollmentResponse(UUID deviceId,
                                     String credentialKeyId,
                                     String secret,
                                     String hmacAlgorithm,
                                     Instant serverTime) {
        this.deviceId = deviceId;
        this.credentialKeyId = credentialKeyId;
        this.secret = secret;
        this.hmacAlgorithm = hmacAlgorithm;
        this.serverTime = serverTime;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getCredentialKeyId() {
        return credentialKeyId;
    }

    public String getSecret() {
        return secret;
    }

    public String getHmacAlgorithm() {
        return hmacAlgorithm;
    }

    public Instant getServerTime() {
        return serverTime;
    }

    @Override
    public String toString() {
        return "ConsumeEnrollmentResponse{"
                + "deviceId=" + deviceId
                + ", credentialKeyId='" + credentialKeyId + '\''
                + ", secret='[REDACTED]'"
                + ", hmacAlgorithm='" + hmacAlgorithm + '\''
                + ", serverTime=" + serverTime
                + '}';
    }
}
