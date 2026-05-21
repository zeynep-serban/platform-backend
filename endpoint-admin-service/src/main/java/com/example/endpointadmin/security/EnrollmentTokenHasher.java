package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

@Component
public class EnrollmentTokenHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    public EnrollmentTokenHasher(
            @Value("${endpoint-admin.enrollment.token-pepper:}") String tokenPepper,
            Environment environment
    ) {
        String material = tokenPepper == null || tokenPepper.isBlank()
                ? fallbackPepper(environment)
                : tokenPepper;
        this.pepper = material.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Enrollment token is required.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return "hmac-sha256:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Enrollment token hash could not be calculated.", ex);
        }
    }

    private static String fallbackPepper(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return "endpoint-admin-local-dev-enrollment-pepper";
            }
        }
        throw new IllegalStateException("endpoint-admin.enrollment.token-pepper must be configured outside local/dev/test.");
    }
}
