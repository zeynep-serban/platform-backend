package com.example.report.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtClaimExtractorTest {

    @Nested
    class ExtractAuditUsername {

        @Test
        void returnsAnonymousWhenJwtIsNull() {
            assertThat(JwtClaimExtractor.extractAuditUsername(null))
                    .isEqualTo(JwtClaimExtractor.FALLBACK_ANONYMOUS);
        }

        @Test
        void returnsEmailWhenPresent() {
            Jwt jwt = jwt("sub1", Map.of("email", "user@example.com", "preferred_username", "user1"));
            assertThat(JwtClaimExtractor.extractAuditUsername(jwt)).isEqualTo("user@example.com");
        }

        @Test
        void fallsBackToPreferredUsernameWhenEmailMissing() {
            Jwt jwt = jwt("sub2", Map.of("preferred_username", "user2"));
            assertThat(JwtClaimExtractor.extractAuditUsername(jwt)).isEqualTo("user2");
        }

        @Test
        void fallsBackToPreferredUsernameWhenEmailBlank() {
            Jwt jwt = jwt("sub3", Map.of("email", "   ", "preferred_username", "user3"));
            assertThat(JwtClaimExtractor.extractAuditUsername(jwt)).isEqualTo("user3");
        }

        @Test
        void fallsBackToSubjectWhenEmailAndPreferredUsernameMissing() {
            Jwt jwt = jwt("sub4", Map.of());
            assertThat(JwtClaimExtractor.extractAuditUsername(jwt)).isEqualTo("sub4");
        }

        @Test
        void returnsAnonymousWhenAllClaimsAndSubjectMissing() {
            Jwt jwt = jwt(null, Map.of());
            assertThat(JwtClaimExtractor.extractAuditUsername(jwt))
                    .isEqualTo(JwtClaimExtractor.FALLBACK_ANONYMOUS);
        }
    }

    @Nested
    class ExtractPreferredUsername {

        @Test
        void returnsSystemWhenJwtIsNull() {
            assertThat(JwtClaimExtractor.extractPreferredUsername(null))
                    .isEqualTo(JwtClaimExtractor.FALLBACK_SYSTEM);
        }

        @Test
        void returnsPreferredUsernameWhenPresent() {
            Jwt jwt = jwt("sub1", Map.of("preferred_username", "user1", "email", "user@example.com"));
            assertThat(JwtClaimExtractor.extractPreferredUsername(jwt)).isEqualTo("user1");
        }

        @Test
        void returnsSystemWhenPreferredUsernameMissing() {
            Jwt jwt = jwt("sub2", Map.of("email", "user@example.com"));
            assertThat(JwtClaimExtractor.extractPreferredUsername(jwt))
                    .isEqualTo(JwtClaimExtractor.FALLBACK_SYSTEM);
        }

        @Test
        void returnsSystemWhenPreferredUsernameBlank() {
            Jwt jwt = jwt("sub3", Map.of("preferred_username", "   "));
            assertThat(JwtClaimExtractor.extractPreferredUsername(jwt))
                    .isEqualTo(JwtClaimExtractor.FALLBACK_SYSTEM);
        }
    }

    private Jwt jwt(String subject, Map<String, Object> claims) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> allClaims = new HashMap<>(claims);
        if (subject != null) {
            allClaims.put("sub", subject);
        }
        if (allClaims.isEmpty()) {
            allClaims.put("placeholder", "ignored"); // Jwt requires at least one claim
        }
        return new Jwt(
                "token-" + (subject != null ? subject : "nosub"),
                Instant.now(),
                Instant.now().plusSeconds(300),
                headers,
                allClaims);
    }
}
