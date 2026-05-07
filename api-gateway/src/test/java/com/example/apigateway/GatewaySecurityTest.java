package com.example.apigateway;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {ApiGatewayApplication.class, GatewaySecurityTest.JwtTestConfig.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
        "spring.main.web-application-type=reactive"
})
class GatewaySecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.core.env.Environment environment;

    static MockWebServer stub;

    @Autowired
    JwtEncoder jwtEncoder;

    @BeforeAll
    static void startWiremock() throws Exception {
        stub = new MockWebServer();
        Dispatcher dispatcher = new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.startsWith("/api/users/all")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"items\":[],\"total\":0}");
                }
                if (path != null && path.equals("/api/users/export.csv")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "text/plain")
                            .setBody("id,fullName\n");
                }
                if (path != null && path.equals("/api/variants/notfound")) {
                    return new MockResponse().setResponseCode(404);
                }
                if (path != null && path.equals("/api/variants/error")) {
                    return new MockResponse().setResponseCode(500);
                }
                // Codex 019dddb7 iter-42 — downstream returns 503 + JSON
                // error body. Gateway MUST forward both the status AND the
                // body intact (no transformation to 200 + empty body, no
                // promotion to 502). Pre-iter-42 behavior occasionally
                // produced 200 + body="" because the response stream was
                // detached before the body was buffered.
                if (path != null && path.equals("/api/variants/degraded")) {
                    return new MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"errorCode\":\"AUTHZ_DEGRADED\",\"message\":\"authz service degraded; retry\"}");
                }
                if (path != null && path.startsWith("/api/variants")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("[]");
                }
                // Faz 23.6 hardening (Codex `019e0494` AGREE iter-1):
                // notification-orchestrator inbox SSE stream stub.
                // Native browser EventSource cannot send Authorization
                // headers, so the gateway lets the bearer token come
                // from the erp_access_token HttpOnly cookie for this
                // exact path. Stub returns text/event-stream so the
                // status-code propagation matches the live behaviour.
                if (path != null && path.startsWith("/api/v1/notify/inbox/me/stream")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "text/event-stream")
                            .setBody(": connected\n\nevent: unread-count\ndata: {\"count\":0}\n\n");
                }
                return new MockResponse().setResponseCode(404);
            }
        };
        stub.setDispatcher(dispatcher);
        stub.start();
    }

    @AfterAll
    static void stopWiremock() throws Exception {
        if (stub != null) stub.shutdown();
    }

    @DynamicPropertySource
    static void routeProps(DynamicPropertyRegistry reg) {
        reg.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "user-service-route");
        reg.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> stub.url("/").toString());
        reg.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/users/**");

        reg.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "variant-service-route");
        reg.add("spring.cloud.gateway.server.webflux.routes[1].uri", () -> stub.url("/").toString());
        reg.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/api/variants/**");

        // Faz 23.6 hardening: notify SSE route exposed on stub for the
        // CookieAwareBearerTokenConverter contract tests below.
        reg.add("spring.cloud.gateway.server.webflux.routes[2].id", () -> "notification-orchestrator-v1-route");
        reg.add("spring.cloud.gateway.server.webflux.routes[2].uri", () -> stub.url("/").toString());
        reg.add("spring.cloud.gateway.server.webflux.routes[2].predicates[0]", () -> "Path=/api/v1/notify/**");

        reg.add("SECURITY_JWT_ISSUER", () -> "auth-service");
        reg.add("SECURITY_JWT_AUDIENCE", () -> "user-service,frontend");
    }

    private String token() {
        return token(List.of("user-service"));
    }

    private String token(List<String> audiences) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin@example.com")
                .issuer("auth-service")
                .audience(audiences)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", 1)
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    @Autowired
    WebTestClient webClient;

    @Test
    void users_requires_jwt() {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev"))) {
            webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                    .exchange()
                    .expectStatus().isOk();
        } else {
            webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void variants_requires_jwt() {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev"))) {
            webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                    .exchange()
                    .expectStatus().isOk();
        } else {
            webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void users_with_jwt_forwards_200() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void users_with_frontendAudienceToken_forwards200() {
        String t = token(List.of("frontend"));
        webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void variants_with_jwt_forwards_200() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void variants_404_500_propagate() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/variants/notfound")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isNotFound();

        webClient.get().uri("http://localhost:" + port + "/api/variants/error")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    // Codex 019dddb7 iter-42 — gateway must forward downstream 503 +
    // JSON error body intact. Pre-iter-42 frontend occasionally saw a
    // 200/empty-body race where the 503 status was lost in the stream
    // hand-off. This test asserts byte-for-byte body preservation AND
    // exact status code 503 (not generalized to 5xx). Combined with
    // permission-service AuthorizationControllerV1 throwing
    // ResponseStatusException(503) and variant-service typed
    // exceptions, the empty-body race is prevented end-to-end.
    @Test
    void downstream_503_status_and_body_preserved_byteForByte() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/variants/degraded")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(body, "503 body must not be null/empty");
                    assertThat(body).contains("AUTHZ_DEGRADED");
                    assertThat(body).contains("authz service degraded");
                });
    }

    // ─── Faz 23.6 hardening (Codex `019e0494` AGREE iter-1) ─────────────
    // SSE inbox stream uses the erp_access_token HttpOnly cookie as the
    // bearer source because native browser EventSource cannot send the
    // Authorization header. The CookieAwareBearerTokenConverter scopes
    // the cookie fallback strictly to GET /api/v1/notify/inbox/me/stream.

    /**
     * Faz 23.6 hardening: SSE cookie auth tests. The
     * {@link CookieAwareBearerTokenConverter} only operates under the
     * production-like {@link SecurityConfig} (profile {@code !local & !dev}).
     * In the {@code local} / {@code dev} profile {@link SecurityConfigLocal}
     * applies and {@code permitAll}s every route, so the cookie path is
     * effectively a no-op there. Each test below mirrors the existing
     * profile-aware pattern (see {@link #users_requires_jwt()}) so the
     * suite passes in both profile contexts and the contract is asserted
     * where it actually applies.
     */
    private boolean isLocalProfile() {
        return environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev"));
    }

    @Test
    void inbox_stream_returns_401_when_no_auth_at_all() {
        if (isLocalProfile()) {
            webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                    .exchange()
                    .expectStatus().isOk();
        } else {
            webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void inbox_stream_authenticates_via_erp_access_token_cookie() {
        String t = token();
        // Both profile branches expect 200; the production branch proves
        // the cookie fallback works end-to-end, the local branch proves
        // the same path is permitAll-friendly under the dev config.
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                .cookie("erp_access_token", t)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/event-stream");
    }

    @Test
    void inbox_stream_rejects_invalid_cookie_token() {
        // In the local profile every request is permitAll'd so even a
        // garbage cookie reaches the stub and gets 200; this test only
        // asserts the production-path contract.
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                .cookie("erp_access_token", "not-a-real-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void inbox_stream_authorization_header_takes_precedence_over_cookie() {
        // Header is the canonical bearer source; cookie fallback only
        // fires when the header is absent. A garbage cookie next to a
        // valid header MUST NOT 401 (otherwise existing fetch-based
        // calls would break). 200 expected in both profile branches.
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                .header("Authorization", "Bearer " + t)
                .cookie("erp_access_token", "garbage")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void cookie_fallback_does_not_authenticate_other_notify_paths() {
        // Strict path scope: even POSTing to a notify path with a valid
        // cookie does NOT authenticate. The cookie fallback is reserved
        // for the SSE stream path only because that is the unique case
        // where the browser cannot attach a header. Missing-or-invalid
        // token paths still 401 as before — but only under the prod
        // profile; local profile permitAll's everything.
        if (isLocalProfile()) {
            return;
        }
        String t = token();
        webClient.post().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/mark-all-read")
                .cookie("erp_access_token", t)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void inbox_stream_no_cookie_no_header_returns_401_not_500() {
        // Defensive: the converter must NOT crash when the cookie is
        // missing. Cookie fallback returns Mono.empty(), the security
        // filter chain proceeds to the standard "no credentials" path,
        // and the configured authenticationEntryPoint emits a JSON 401.
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/inbox/me/stream?orgId=default&subscriberId=1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/json")
                .expectBody()
                .jsonPath("$.error").isEqualTo("unauthorized");
    }

    @Test
    void export_injects_pii_policy_header() throws Exception {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/users/export.csv")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();

        // Kuyruktan export isteğini yakala ve header'ı doğrula
        RecordedRequest req;
        RecordedRequest exportReq = null;
        for (int i = 0; i < 10; i++) {
            req = stub.takeRequest();
            if (req == null) break;
            if ("/api/users/export.csv".equals(req.getPath())) { exportReq = req; break; }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(exportReq, "export request not captured");
        org.junit.jupiter.api.Assertions.assertEquals("mask", exportReq.getHeader("X-PII-Policy"));
    }

    @Configuration
    static class JwtTestConfig {
        @Bean
        public com.nimbusds.jose.jwk.RSAKey testRsaKey() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("test-kid")
                    .build();
        }

        @Bean
        @Primary
        public JwtEncoder testJwtEncoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(testRsaKey));
            return new NimbusJwtEncoder(jwkSource);
        }

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            try {
                return NimbusJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
        }

        @Bean
        @Primary
        public org.springframework.security.oauth2.jwt.ReactiveJwtDecoder testReactiveJwtDecoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            try {
                return org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
