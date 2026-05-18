package com.example.user.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.example.user.repository.UserRepository;
import com.example.user.model.User;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // RS256/JWKS doğrulaması için beklenen issuer/audience
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_ISSUERS=https://ai.acik.com/realms/serban",
        "SECURITY_JWT_AUDIENCE=user-service",
        // Testlerde bean override'a izin ver (serviceRsaKey testte geçersiz kılınacak)
        "spring.main.allow-bean-definition-overriding=true",
        // Testlerde H2 kullan, Flyway kapalı; JPA şema oluşturur
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class UserSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.example.user.repository.UserAuditEventRepository userAuditEventRepository;

    @org.springframework.beans.factory.annotation.Value("${export.rate-limit.burst:24}")
    private int exportBurstCapacity;

    private final java.util.concurrent.atomic.AtomicLong userIdSequence = new java.util.concurrent.atomic.AtomicLong(1);

    @AfterEach
    void cleanup() {
        userAuditEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String issueToken(String audience) {
        // The `seq` only varies the email so each test gets a distinct
        // user; the JWT `userId` claim is set to the real persisted row
        // id by issueToken(audience, email) below.
        long seq = userIdSequence.getAndIncrement();
        String email = "admin+" + seq + "@example.com";
        return issueToken(audience, email);
    }

    private String issueToken(String audience, long seq) {
        String email = "admin+" + seq + "@example.com";
        return issueToken(audience, email);
    }

    /**
     * Issues an RS256 token whose {@code userId} claim is the real id of
     * the (created-if-absent) {@code users} row for {@code email}.
     *
     * <p>This mirrors how auth-service mints tokens — the {@code userId}
     * claim is the authoritative backend user id. The unified
     * {@code CurrentUserResolver} resolves a numeric {@code userId} via
     * {@code findById}, so the claim must match the persisted id.
     */
    private String issueToken(String audience, String email) {
        User user = ensureUserExists(email);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .issuer("auth-service")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("userId", user.getId())
                .claim("role", "ADMIN")
                .claim("permissions", List.of("VIEW_USERS"))
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    private User ensureUserExists(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setName(email);
            u.setEmail(email);
            u.setPassword("test");
            u.setRole("ADMIN");
            return userRepository.saveAndFlush(u);
        });
    }

    @Test
    void users_all_should_return_401_without_token() throws Exception {
        mockMvc.perform(get("/api/users/all?page=1&pageSize=2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void users_all_should_return_200_with_valid_rs256_jwt() throws Exception {
        String token = issueToken("user-service");
        mockMvc.perform(get("/api/users/all?page=1&pageSize=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void users_all_should_return_401_when_audience_mismatch() throws Exception {
        String token = issueToken("variant-service");
        mockMvc.perform(get("/api/users/all?page=1&pageSize=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void users_all_should_return_200_when_authorized_party_matches() throws Exception {
        User azpUser = ensureUserExists("azp@example.com");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("azp@example.com")
                .issuer("auth-service")
                .audience(List.of("other-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", "azp@example.com")
                .claim("userId", azpUser.getId())
                .claim("azp", "frontend")
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        mockMvc.perform(get("/api/users/all?page=1&pageSize=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void users_all_should_return_200_for_secondary_public_issuer() throws Exception {
        User publicIssuerUser = ensureUserExists("public-issuer@example.com");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("public-issuer@example.com")
                .issuer("https://ai.acik.com/realms/serban")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", "public-issuer@example.com")
                .claim("userId", publicIssuerUser.getId())
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        mockMvc.perform(get("/api/users/all?page=1&pageSize=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void export_csv_should_stream_with_headers() throws Exception {
        // seed a couple of users
        User u1 = new User();
        u1.setName("Alice Seed");
        u1.setEmail("alice.seed@example.com");
        u1.setPassword("x");
        userRepository.save(u1);
        User u2 = new User();
        u2.setName("Bob Seed");
        u2.setEmail("bob.seed@example.com");
        u2.setPassword("x");
        userRepository.save(u2);

        String token = issueToken("user-service");
        MvcResult res = mockMvc.perform(get("/api/users/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String cd = res.getResponse().getHeader("Content-Disposition");
        String body = res.getResponse().getContentAsString();

        assertThat(cd).contains("users-export.csv");
        assertThat(body).contains("ID;Ad Soyad;E-posta;Rol;Durum");
    }

    @Test
    void advancedFilter_contains_email_should_filter_results() throws Exception {
        // ensure a known user exists
        User u = new User();
        u.setName("Filter User");
        u.setEmail("filter.user@example.com");
        u.setPassword("x");
        userRepository.save(u);

        String afJson = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"email\",\"op\":\"contains\",\"value\":\"filter.user@\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        String token = issueToken("user-service");
        MvcResult res = mockMvc.perform(get("/api/users/all?page=1&pageSize=50&advancedFilter=" + afJson)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("filter.user@example.com");
    }

    @Test
    void advancedFilter_dates_and_numbers_type_safe_ops() throws Exception {
        // seed with known values
        User u1 = new User();
        u1.setName("NumDate A");
        u1.setEmail("a.numdate@example.com");
        u1.setPassword("x");
        u1.setCreateDate(java.time.LocalDateTime.parse("2025-01-01T00:00:00"));
        u1.setSessionTimeoutMinutes(10);
        userRepository.save(u1);

        User u2 = new User();
        u2.setName("NumDate B");
        u2.setEmail("b.numdate@example.com");
        u2.setPassword("x");
        u2.setCreateDate(java.time.LocalDateTime.parse("2025-01-10T00:00:00"));
        u2.setSessionTimeoutMinutes(30);
        userRepository.save(u2);

        String token = issueToken("user-service");

        // lessThan on number
        String f1 = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"sessionTimeoutMinutes\",\"op\":\"lessThan\",\"value\":\"20\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=50&advancedFilter=" + f1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // greaterThan on date
        String f2 = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"createDate\",\"op\":\"greaterThan\",\"value\":\"2025-01-05T00:00:00\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=50&advancedFilter=" + f2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // invalid: contains on date → 400
        String bad = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"createDate\",\"op\":\"contains\",\"value\":\"2025\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=1&advancedFilter=" + bad)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_csv_large_dataset_streams_ok() throws Exception {
        for (int i = 0; i < 1500; i++) {
            User u = new User();
            u.setName("Bulk-" + i);
            u.setEmail("bulk" + i + "@example.com");
            u.setPassword("x");
            userRepository.save(u);
        }
        String token = issueToken("user-service");
        mockMvc.perform(get("/api/users/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void export_csv_rate_limit_blocks_after_burst_capacity() throws Exception {
        String token = issueToken("user-service", 9_999L);
        for (int i = 0; i < exportBurstCapacity; i++) {
            mockMvc.perform(get("/api/users/export.csv")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/users/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void export_csv_records_audit_event() throws Exception {
        User seed = new User();
        seed.setName("Audit Export");
        seed.setEmail("audit.export@example.com");
        seed.setPassword("x");
        userRepository.save(seed);

        String token = issueToken("user-service", 8_888L);
        mockMvc.perform(get("/api/users/export.csv")
                        .param("search", "audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        awaitAuditEvents(1);
        var events = userAuditEventRepository.findAll();
        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.getEventType()).isEqualTo("USER_EXPORT");
        assertThat(event.getDetails()).contains("success=true").contains("search='audit'");
    }

    @Test
    void advancedFilter_with_invalid_field_returns_400() throws Exception {
        String token = issueToken("user-service");
        String afBad = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"nonField\",\"op\":\"contains\",\"value\":\"x\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=1&advancedFilter=" + afBad)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void advancedFilter_with_invalid_operator_returns_400() throws Exception {
        String token = issueToken("user-service");
        String afBadOp = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"email\",\"op\":\"startsWith\",\"value\":\"test\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=1&advancedFilter=" + afBadOp)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void advancedFilter_malformed_json_returns_400() throws Exception {
        String token = issueToken("user-service");
        // Sadece '{' gönder (decode sonrası geçersiz JSON)
        String malformed = java.net.URLEncoder.encode("{", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=1&advancedFilter=" + malformed)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void advancedFilter_inRange_without_value2_returns_400() throws Exception {
        String token = issueToken("user-service");
        String noValue2 = java.net.URLEncoder.encode("{\"logic\":\"and\",\"conditions\":[{\"field\":\"createDate\",\"op\":\"inRange\",\"value\":\"2025-01-01\"}]}", java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get("/api/users/all?page=1&pageSize=1&advancedFilter=" + noValue2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class JwtTestConfig {
        @Bean(name = "serviceRsaKey")
        @Primary
        public RSAKey serviceRsaKeyForTests() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("test-kid")
                    .build();
        }

        @Bean
        @Primary
        public JwtEncoder testJwtEncoder(RSAKey serviceRsaKey) {
            JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(serviceRsaKey));
            return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(jwkSource);
        }

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder(RSAKey serviceRsaKey) {
            NimbusJwtDecoder primary;
            NimbusJwtDecoder publicIssuer;
            try {
                primary = NimbusJwtDecoder.withPublicKey(serviceRsaKey.toRSAPublicKey()).build();
                publicIssuer = NimbusJwtDecoder.withPublicKey(serviceRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
            var audienceOrAuthorizedPartyValidator = new AudienceOrAuthorizedPartyValidator(
                    java.util.List.of("user-service"),
                    java.util.List.of("frontend", "admin-cli", "serban-web", "account")
            );
            primary.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("auth-service"),
                    audienceOrAuthorizedPartyValidator
            ));
            publicIssuer.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("https://ai.acik.com/realms/serban"),
                    audienceOrAuthorizedPartyValidator
            ));
            return token -> {
                try {
                    return primary.decode(token);
                } catch (Exception ignored) {
                    return publicIssuer.decode(token);
                }
            };
        }

        /**
         * StreamingResponseBody varsayılan olarak ayrı bir iş parçacığında çalıştığı için
         * MockMvc ile eşzamanlı header yazımları ConcurrentModificationException fırlatabiliyordu.
         * Test ortamında streaming işlemini senkron yürütmek, gerçek davranışı bozmadan bu yarışı engelliyor.
         */
        @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
        @Primary
        public TaskExecutor applicationTaskExecutor() {
            return new SyncTaskExecutor();
        }

        /**
         * CsvExportGuardService'in TokenBucket'ı bu Clock üzerinden akar.
         * Fixed clock => refill() daima 0 saniye görür, wall-clock jitter'ı (MacOS'ta
         * permission-service DNS time-out'u ~5s) rate-limit testini non-deterministic
         * yapmaz.
         */
        @Bean
        @Primary
        public Clock testClock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    private void awaitAuditEvents(long expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (userAuditEventRepository.count() == expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(userAuditEventRepository.count()).isEqualTo(expectedCount);
    }
}
