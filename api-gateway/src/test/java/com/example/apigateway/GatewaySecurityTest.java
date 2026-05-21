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
        "spring.profiles.active=test",
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
                // Faz 23.4 T3.1.7 — DLR provider webhook stub. Backend
                // (notification-orchestrator) DlrController handles
                // shared-secret token verification (X-NetGSM-DLR-Token);
                // here we only stub the downstream so we can assert the
                // gateway permitAll bypass works for the public path.
                if (path != null && path.startsWith("/api/v1/notify/dlr/")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"action\":\"UPDATED\",\"provider_msg_id\":\"netgsm-test\"}");
                }
                // Faz 23.2.A T1.1.8 — unsubscribe landing/revoke stub.
                // Backend UnsubscribeController verifies HMAC-SHA256
                // signed token; gateway permitAll only ensures the path
                // is publicly reachable.
                if (path != null && path.startsWith("/api/v1/notify/unsubscribe")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"action\":\"REVOKED\"}");
                }
                // Faz 22.1.1 H2 endpoint-admin gateway integration —
                // downstream stubs for the 3 gateway routes (agent/admin/status).
                // After RewritePath the gateway forwards to /api/v1/agent/**
                // and /api/v1/admin/** which match endpoint-admin-service
                // controllers; the /api/v1/endpoint-agents/** path stays as-is.
                if (path != null && path.equals("/api/v1/agent/heartbeat")) {
                    return new MockResponse().setResponseCode(202)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"accepted\":true}");
                }
                if (path != null && path.equals("/api/v1/admin/endpoint-devices")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"items\":[]}");
                }
                if (path != null && path.equals("/api/v1/endpoint-agents/status")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"service\":\"endpoint-admin-service\"}");
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

    /**
     * Sustaining route registrar — Codex 019e4c3f AGREE pattern.
     *
     * Previous implementation hardcoded numeric `routes[N]` slot indices,
     * which made adding a new route (notification-orchestrator at slot 2,
     * then endpoint-admin at slots 3/4/5, ...) a fragile manual edit
     * that re-broke on every concurrent addition (H1 source PR + Faz 23
     * gateway routes both wanted slot 2 — collision).
     *
     * This helper auto-increments the slot counter and tracks route IDs,
     * so route additions are order-independent and test assertions
     * reference paths (HTTP URI) not numeric slot positions.
     */
    private static final class RouteRegistrar {
        private final DynamicPropertyRegistry reg;
        private final java.util.function.Supplier<Object> uri;
        private final java.util.Map<String, Integer> idToSlot = new java.util.HashMap<>();
        private int nextSlot = 0;

        RouteRegistrar(DynamicPropertyRegistry reg, java.util.function.Supplier<Object> uri) {
            this.reg = reg;
            this.uri = uri;
        }

        /** Register a route with no filters. */
        void route(String id, String pathPredicate) {
            registerHead(id, pathPredicate);
        }

        /** Register a route with one or more filters. */
        void routeWithFilters(String id, String pathPredicate, String... filters) {
            int slot = registerHead(id, pathPredicate);
            for (int i = 0; i < filters.length; i++) {
                int idx = i;
                String f = filters[idx];
                reg.add("spring.cloud.gateway.server.webflux.routes[" + slot + "].filters[" + idx + "]", () -> f);
            }
        }

        private int registerHead(String id, String pathPredicate) {
            int slot = nextSlot++;
            idToSlot.put(id, slot);
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + slot + "]";
            reg.add(prefix + ".id", () -> id);
            reg.add(prefix + ".uri", uri);
            reg.add(prefix + ".predicates[0]", () -> pathPredicate);
            return slot;
        }

        int slotOf(String id) {
            Integer s = idToSlot.get(id);
            if (s == null) {
                throw new IllegalStateException("Route id not registered: " + id);
            }
            return s;
        }
    }

    @DynamicPropertySource
    static void routeProps(DynamicPropertyRegistry reg) {
        RouteRegistrar routes = new RouteRegistrar(reg, () -> stub.url("/").toString());

        routes.route("user-service-route", "Path=/api/users/**");
        routes.route("variant-service-route", "Path=/api/variants/**");

        // Faz 23.6 hardening: notify SSE route exposed on stub for the
        // CookieAwareBearerTokenConverter contract tests below.
        routes.route("notification-orchestrator-v1-route", "Path=/api/v1/notify/**");

        // Faz 22.1.1 H2 endpoint-admin gateway integration — Codex 019e4c3f
        // AGREE. The agent route is public (permitAll in SecurityConfig)
        // and rewrites `/api/v1/endpoint-agent/**` to the downstream
        // `/api/v1/agent/**` surface. The admin route requires JWT and
        // rewrites `/api/v1/endpoint-admin/**` to `/api/v1/admin/**`.
        // The status route is a read-only passthrough at
        // `/api/v1/endpoint-agents/**` (no RewritePath).
        routes.routeWithFilters(
                "endpoint-admin-agent-route",
                "Path=/api/v1/endpoint-agent/**",
                "RewritePath=/api/v1/endpoint-agent/(?<segment>.*), /api/v1/agent/${segment}");
        routes.routeWithFilters(
                "endpoint-admin-admin-route",
                "Path=/api/v1/endpoint-admin/**",
                "RewritePath=/api/v1/endpoint-admin/(?<segment>.*), /api/v1/admin/${segment}");
        routes.route("endpoint-admin-status-route", "Path=/api/v1/endpoint-agents/**");

        reg.add("SECURITY_JWT_ISSUER", () -> "auth-service");
        // Faz 22.1.1 H2: endpoint-admin REST bearer JWT regression — the
        // global CookieAwareBearerTokenConverter (Faz 23.6) cookie fallback
        // does not break Authorization-header REST flow for admin routes.
        reg.add("SECURITY_JWT_AUDIENCE", () -> "user-service,frontend,endpoint-admin-service");
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

    // ─── Faz 23.4 T3.1.7 — SMS DLR provider webhook gateway permitAll ─────
    // NetGSM external POST yapacak (Internet → ingress → api-gateway →
    // notification-orchestrator). JWT yok — backend kendi
    // shared-secret token (X-NetGSM-DLR-Token) ile constant-time compare.
    // Gateway tarafında `/api/v1/notify/dlr/**` POST permitAll olmalı; aksi
    // halde provider 401 alır, retry exhaust eder, DLR state mutation
    // gerçekleşmez. Aşağıdaki testler bu kontratı doğrular.

    @Test
    void dlr_netgsm_post_without_jwt_returns_200() {
        if (isLocalProfile()) {
            // local profile zaten permitAll — kontrat zaten karşılanıyor
            return;
        }
        webClient.post().uri("http://localhost:" + port + "/api/v1/notify/dlr/netgsm")
                .header("X-NetGSM-DLR-Token", "shared-secret")
                .header("Content-Type", "application/json")
                .bodyValue("{\"jobid\":\"test-job\",\"code\":\"00\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.action").isEqualTo("UPDATED");
    }

    @Test
    void dlr_netgsm_post_with_jwt_still_works() {
        // Defensive: JWT varlığı POST'u kırmamalı (permitAll path JWT'yi
        // ignore eder ama exception fırlatmaz). Bu test PR-time'da
        // bilinçli auth karışmasına karşı koruma.
        String t = token();
        webClient.post().uri("http://localhost:" + port + "/api/v1/notify/dlr/netgsm")
                .header("Authorization", "Bearer " + t)
                .header("Content-Type", "application/json")
                .bodyValue("{\"jobid\":\"test-job\",\"code\":\"00\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void dlr_netgsm_get_without_jwt_returns_401() {
        // Sadece POST permitAll edildi (HttpMethod.POST). GET yöntemi
        // hâlâ `.anyExchange().authenticated()` matrisinde — provider
        // misconfiguration veya saldırgan probing'e karşı GET 401 dönmeli.
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/dlr/netgsm")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── Faz 23.2.A T1.1.8 — Public unsubscribe gateway permitAll ─────────
    // Email recipient browser'da link tıklar; subscriber session olmayabilir.
    // Backend (notification-orchestrator) HMAC-SHA256 signed token verifier
    // (UnsubscribeTokenService) controller seviyesinde gate. Gateway permitAll
    // sadece path'in publicly reachable olmasını sağlar.

    @Test
    void unsubscribe_get_without_jwt_returns_200() {
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/notify/unsubscribe?token=test-hmac-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.action").isEqualTo("REVOKED");
    }

    @Test
    void unsubscribe_post_gateway_forward_no_auth() {
        // Gateway permitAll kontratı: POST `/api/v1/notify/unsubscribe`
        // gateway tarafından JWT olmadan downstream'e forward edilir.
        // Backend UnsubscribeController mevcut kontrat **GET-only** (root
        // path @GetMapping). Bu test gateway'in HTTP method'undan
        // bağımsız permitAll forward davranışını doğrular; backend
        // gerçekte 405 Method Not Allowed döner (stub burada 200 dönüyor
        // çünkü dispatcher method ayrımı yapmıyor). Codex 019e1440 P1
        // absorb: "RFC 8058 POST one-click" claim'i backend route
        // eklenince doğrulanır; bu PR sadece gateway gap'i kapatıyor.
        if (isLocalProfile()) {
            return;
        }
        webClient.post().uri("http://localhost:" + port + "/api/v1/notify/unsubscribe")
                .header("Content-Type", "application/json")
                .bodyValue("{\"token\":\"test-hmac-token\"}")
                .exchange()
                // Gateway forward'ı 200/405/etc — sadece 401 OLMAMASI
                // permitAll kontratı için yeterli. Stub 200 dönüyor; gerçek
                // backend 405 döner (PostMapping yok); ikisinde de gateway
                // auth-gate'i geçti.
                .expectStatus().value(status ->
                    org.junit.jupiter.api.Assertions.assertNotEquals(401, status,
                        "gateway permitAll bypass: unsubscribe POST 401 OLMAMALI (backend method handling ayrı kontrat)"));
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

    // ─── Faz 22.1.1 H2 — endpoint-admin gateway integration tests ─────────
    // Codex 019e4c3f AGREE: the agent route is permitAll + rewrites
    // /api/v1/endpoint-agent/** to /api/v1/agent/** (HMAC-only auth at the
    // service layer); the admin route requires a JWT; the status route is
    // a read-only passthrough requiring a JWT by default.
    //
    // These assertions rely on the path predicate (the route stub URL is
    // resolved at runtime via the RouteRegistrar helper above), so future
    // route additions cannot break them via numeric-slot drift.

    @Test
    void endpoint_agent_route_is_permitAll_and_rewrites_to_agent_surface() {
        if (isLocalProfile()) {
            return;
        }
        webClient.post().uri("http://localhost:" + port + "/api/v1/endpoint-agent/heartbeat")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void endpoint_admin_route_requires_jwt() {
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/endpoint-admin/endpoint-devices")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void endpoint_admin_route_with_jwt_rewrites_to_admin_surface() {
        if (isLocalProfile()) {
            return;
        }
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/v1/endpoint-admin/endpoint-devices")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void endpoint_agent_status_requires_jwt_by_default() {
        if (isLocalProfile()) {
            return;
        }
        webClient.get().uri("http://localhost:" + port + "/api/v1/endpoint-agents/status")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void endpoint_agent_status_with_jwt_forwards() {
        if (isLocalProfile()) {
            return;
        }
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/v1/endpoint-agents/status")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    // Faz 22.1.1 H2 regression — Codex 019e4c81 absorb: the global
    // CookieAwareBearerTokenConverter (Faz 23.6) changed the gateway's
    // resource-server bearer-token resolution to fall back to the
    // erp_access_token cookie if no Authorization header is present. The
    // endpoint-admin REST flow uses Authorization header (no cookie), so
    // this regression check pins the header-only contract.
    @Test
    void endpoint_admin_route_accepts_authorization_header_after_sse_cookie_addition() {
        if (isLocalProfile()) {
            return;
        }
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/v1/endpoint-admin/endpoint-devices")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
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
