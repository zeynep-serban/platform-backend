package com.example.endpointadmin.security;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator(
            List.of("endpoint-admin-service"),
            List.of("frontend")
    );

    @Test
    void acceptsExpectedAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .audience(List.of("endpoint-admin-service"))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        Assertions.assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void acceptsAllowedAuthorizedParty() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .audience(List.of("other-service"))
                .claims(claims -> claims.putAll(Map.of("azp", "frontend")))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        Assertions.assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsUnexpectedAudienceAndClient() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .audience(List.of("other-service"))
                .claims(claims -> claims.putAll(Map.of("azp", "unknown-client")))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        Assertions.assertThat(result.hasErrors()).isTrue();
    }
}
