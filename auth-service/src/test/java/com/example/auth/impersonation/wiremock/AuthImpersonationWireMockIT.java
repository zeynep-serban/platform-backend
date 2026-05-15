package com.example.auth.impersonation.wiremock;

import com.example.auth.impersonation.KeycloakBrokerClient;
import com.example.auth.serviceauth.ServiceTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Auth-service Impersonation Broker WireMock IT — Faz 1 (Codex
 * {@code 019e2022} strategy AGREE'd; HARD RULE Plan Consensus Autonomy +
 * Reviewer ≠ Implementer cross-AI peer review compliant).
 *
 * <p>Phase 1 case set (this PR):
 * <ol>
 *   <li>{@code happy_full_chain} — permission-service contract handoff
 *       verified (start-session 201 + sessionId/exchangedToken/expiresAt).</li>
 *   <li>{@code self_impersonation_pre_resolution} — BUG #1 IT-level proof:
 *       targetEmail equal to admin → 403 SELF_IMPERSONATION_FORBIDDEN +
 *       audit-events BLOCKED row with non-null {@code targetEmail}.</li>
 *   <li>{@code validation_empty_reason} — empty reason → 400 VALIDATION_ERROR
 *       with a {@code reason} field error.</li>
 * </ol>
 *
 * <p>Phase 2 (spawn task chip): disabled / unresolvable / role-mismatch /
 * concurrent / revoke cases. Build on the same WireMock fixture.
 *
 * <p>Boundary (ADR-0011 §2.3): test-only. No runtime overlay / secret /
 * state mutation. {@code @MockBean KeycloakBrokerClient} +
 * {@code @MockBean ServiceTokenProvider} keep the JWT signing chain out
 * of scope; user-service + permission-service wire contracts are exercised
 * through WireMock.
 */
@SpringBootTest
@ActiveProfiles("wiremock-it")
class AuthImpersonationWireMockIT {

    private static final WireMockServer wireMock =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @MockBean
    private KeycloakBrokerClient keycloakBrokerClient;

    @MockBean
    private ServiceTokenProvider serviceTokenProvider;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterAll
    static void stopWireMock() {
        if (wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void overrideBaseUrls(DynamicPropertyRegistry registry) {
        if (!wireMock.isRunning()) {
            wireMock.start();
        }
        registry.add("user.service.base-url", wireMock::baseUrl);
        registry.add("permission.service.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        wireMock.resetAll();
        // ServiceTokenProvider mock returns a placeholder; UserServiceClient
        // forwards it as a bearer header that the WireMock stubs accept via
        // a permissive matcher (we don't assert on the literal value).
        org.mockito.Mockito.when(serviceTokenProvider.getToken(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(TestTokens.SERVICE_TOKEN_PLACEHOLDER);
    }

    @AfterEach
    void clearWireMock() {
        wireMock.resetRequests();
    }

    // ------------------------------------------------------------------
    // Stub helpers — each test composes the stubs it needs from this set.
    // ------------------------------------------------------------------

    /** Stub permission-service /authz/me with the given superAdmin flag. */
    void stubAuthzMe(boolean superAdmin) {
        String body = "{\"superAdmin\":" + superAdmin + ",\"organizationId\":\"default\"}";
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/authz/me"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }

    /**
     * Stub user-service impersonation-target lookup. {@code enabled=false}
     * exercises the disabled-target branch; null {@code kcSubject} exercises
     * the unresolvable branch.
     */
    void stubUserServiceTarget(long targetUserId, String kcSubject, String email, boolean enabled) {
        StringBuilder body = new StringBuilder();
        body.append("{\"id\":").append(targetUserId)
                .append(",\"email\":\"").append(email).append("\"")
                .append(",\"name\":\"Target Test User\"")
                .append(",\"role\":\"USER\"")
                .append(",\"enabled\":").append(enabled);
        if (kcSubject != null) {
            body.append(",\"kcSubject\":\"").append(kcSubject).append("\"");
        }
        body.append("}");
        wireMock.stubFor(get(urlPathEqualTo("/api/users/internal/" + targetUserId + "/impersonation-target"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body.toString())));
    }

        /** Stub permission-service session create — happy 201 response. */
    void stubSessionCreate201(String sessionId, long impersonatorUserId, long targetUserId) {
        String body = "{\"sessionId\":\"" + sessionId + "\""
                + ",\"impersonatorUserId\":" + impersonatorUserId
                + ",\"targetUserId\":" + targetUserId
                + ",\"startedAt\":\"2026-05-14T00:00:00Z\""
                + ",\"expiresAt\":\"2026-05-14T01:00:00Z\""
                + ",\"status\":\"ACTIVE\"}";
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/internal/impersonation/sessions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }

    /** Stub permission-service audit-events sink — always accepts. */
    void stubAuditAccepted() {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/internal/impersonation/audit-events"))
                .willReturn(aResponse().withStatus(202)));
    }

    /** Read the JSON body of the last POST to a given path; null if none. */
    JsonNode lastBodyTo(String path) throws Exception {
        var events = wireMock.findAll(WireMock.postRequestedFor(urlPathEqualTo(path)));
        if (events.isEmpty()) {
            return null;
        }
        return objectMapper.readTree(events.get(events.size() - 1).getBodyAsString());
    }

    /** Count POST requests recorded against the given path. */
    int countPostsTo(String path) {
        return wireMock.findAll(WireMock.postRequestedFor(urlPathEqualTo(path))).size();
    }

    /** Count GET requests recorded against the given path. */
    int countGetsTo(String path) {
        return wireMock.findAll(WireMock.getRequestedFor(urlPathEqualTo(path))).size();
    }

    // ------------------------------------------------------------------
    // Faz 1 case set — happy + BUG#1 + validation.
    // ------------------------------------------------------------------

    @Test
    void happy_full_chain() throws Exception {
        // arrange
        long impersonatorUserId = 1L;
        long targetUserId = 42L;
        String targetSubject = "target-kc-subject";
        String targetEmail = "halil.kocoglu@example.com";

        stubAuthzMe(true);
        stubUserServiceTarget(targetUserId, targetSubject, targetEmail, true);
        UUID sessionId = UUID.randomUUID();
        stubSessionCreate201(sessionId.toString(), impersonatorUserId, targetUserId);
        stubAuditAccepted();

        String fakeExchangedJwt = TestTokens.buildFakeExchangedJwt(
                "https://wiremock-it/realms/test",
                "jti-happy-1",
                "sid-happy-1",
                targetSubject,
                "impersonation-broker",
                9_999_999_999L,
                targetEmail);
        KeycloakBrokerClient.DecodedClaims claims = new KeycloakBrokerClient.DecodedClaims(
                "https://wiremock-it/realms/test",
                "jti-happy-1",
                "sid-happy-1",
                targetSubject,
                "impersonation-broker",
                9_999_999_999L,
                targetEmail);
        when(keycloakBrokerClient.exchange(any(), eq(targetSubject)))
                .thenReturn(new KeycloakBrokerClient.ExchangeResult(fakeExchangedJwt, 3600L, claims));

        // act
        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-kc-subject")
                                .claim("userId", impersonatorUserId)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"Faz 1 happy path proof — wiremock chain\"}"))
                .andReturn();

        // assert response shape — permission-service contract handoff verified
        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(201);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(response.get("exchangedToken").asText()).isEqualTo(fakeExchangedJwt);

        // assert downstream contract — start-session POST body shape
        JsonNode sessionBody = lastBodyTo("/api/v1/internal/impersonation/sessions");
        assertThat(sessionBody).isNotNull();
        assertThat(sessionBody.get("impersonatorUserId").asLong()).isEqualTo(impersonatorUserId);
        assertThat(sessionBody.get("targetUserId").asLong()).isEqualTo(targetUserId);
        assertThat(sessionBody.get("targetSubject").asText()).isEqualTo(targetSubject);
        assertThat(sessionBody.get("targetEmail").asText()).isEqualTo(targetEmail);
        assertThat(sessionBody.get("issuer").asText()).isEqualTo("https://wiremock-it/realms/test");
        assertThat(sessionBody.get("jti").asText()).isEqualTo("jti-happy-1");
        assertThat(sessionBody.get("sid").asText()).isEqualTo("sid-happy-1");
        assertThat(sessionBody.get("reason").asText()).contains("Faz 1 happy path proof");

        // assert wire contract headers (Codex REVISE-2)
        assertHappyPathHeaders(targetUserId);
    }

    @Test
    void self_impersonation_pre_resolution_emits_blocked_audit() throws Exception {
        // arrange — only the audit sink is stubbed; user-service and session
        // create endpoints must NOT be called (Step 1b SELF guard fires first).
        stubAuditAccepted();
        long adminUserId = 1L;
        String adminEmail = "admin@example.com";

        // act
        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-kc-subject")
                                .claim("userId", adminUserId)
                                .claim("email", adminEmail)
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + adminUserId
                                + ",\"targetEmail\":\"" + adminEmail + "\""
                                + ",\"reason\":\"BUG#1 IT-level retest — self guard\"}"))
                .andReturn();

        // assert response — 403 with the self-guard error code
        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(403);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("errorCode").asText()).isEqualTo("SELF_IMPERSONATION_FORBIDDEN");

        // BUG #1 proof — BLOCKED audit row carries the targetEmail
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("eventType").asText()).isEqualTo("IMPERSONATION_BLOCKED");
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("SELF_IMPERSONATION_FORBIDDEN");
        assertThat(auditBody.get("targetEmail").asText()).isEqualTo(adminEmail);
        assertThat(auditBody.get("targetUserId").asLong()).isEqualTo(adminUserId);

        // assert zero side-effects on the resolution + session chain
        assertThat(countGetsTo("/api/users/internal/" + adminUserId + "/impersonation-target"))
                .as("user-service must not be called when self-impersonation is rejected pre-resolution")
                .isZero();
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions"))
                .as("permission-service session create must not be called")
                .isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());
    }

    @Test
    void validation_empty_reason_short_circuits() throws Exception {
        // arrange — no downstream stubs; @Valid should fail before anything fires.
        stubAuditAccepted(); // tolerated even if never called

        // act
        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-kc-subject")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":42,\"reason\":\"\"}"))
                .andReturn();

        // assert — bean validation error with a reason field entry
        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("VALIDATION_ERROR");
        JsonNode fieldErrors = body.get("fieldErrors");
        assertThat(fieldErrors).isNotNull();
        assertThat(fieldErrors.isArray()).isTrue();

        boolean hasReasonField = false;
        for (JsonNode entry : fieldErrors) {
            if ("reason".equals(entry.path("field").asText())) {
                hasReasonField = true;
                assertThat(entry.path("message").asText()).isNotEmpty();
            }
        }
        assertThat(hasReasonField)
                .as("validation response must include a 'reason' field error")
                .isTrue();

        // assert zero downstream calls (Codex REVISE-2: include audit sink)
        assertThat(countGetsTo("/api/users/internal/42/impersonation-target")).isZero();
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isZero();
        assertThat(countPostsTo("/api/v1/internal/impersonation/audit-events")).isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());
    }

    // ------------------------------------------------------------------
    // Wire contract header assertions (Codex REVISE-2 optional, absorbed):
    // verify that internal HTTP calls carry the auth headers configured by
    // the SERVICE_AUTH / internal-api-key drift guards.
    // ------------------------------------------------------------------

    /** Last request to a given path (POST or GET), or null if none. */
    private com.github.tomakehurst.wiremock.verification.LoggedRequest lastRequestTo(String path) {
        var all = wireMock.getAllServeEvents();
        com.github.tomakehurst.wiremock.verification.LoggedRequest match = null;
        for (var ev : all) {
            if (path.equals(ev.getRequest().getUrl().split("\\?")[0])) {
                match = ev.getRequest();
            }
        }
        return match;
    }

    /** Header assertions shared by the happy case — kept as a separate
     *  helper so Phase 2 can reuse it. */
    private void assertHappyPathHeaders(long targetUserId) {
        var userServiceReq = lastRequestTo(
                "/api/users/internal/" + targetUserId + "/impersonation-target");
        assertThat(userServiceReq)
                .as("user-service internal lookup must have been called")
                .isNotNull();
        assertThat(userServiceReq.getHeader("Authorization"))
                .as("user-service call must forward a service-token bearer")
                .startsWith("Bearer ");

        var sessionReq = lastRequestTo("/api/v1/internal/impersonation/sessions");
        assertThat(sessionReq).isNotNull();
        assertThat(sessionReq.getHeader("X-Internal-Api-Key"))
                .as("permission-service session create must carry the internal API key")
                .isNotEmpty();
    }

    /** Faz 2 helper — stub permission-service session create to 409. */
    void stubSessionCreate409() {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/api/v1/internal/impersonation/sessions"))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"errorCode\":\"ACTIVE_IMPERSONATION_EXISTS\","
                                + "\"message\":\"Aktif oturum mevcut\"}")));
    }

    /** Faz 2 helper — stub stopSession (DELETE on internal session row). */
    void stubSessionStop204(String sessionId) {
        wireMock.stubFor(WireMock.delete(urlPathEqualTo(
                        "/api/v1/internal/impersonation/sessions/" + sessionId))
                .willReturn(aResponse().withStatus(204)));
    }

    /** Faz 2 helper — fetch active session info (used by DELETE /current path). */
    void stubActiveSessionExists(String sessionId, long impersonatorUserId, long targetUserId) {
        String body = "{\"sessionId\":\"" + sessionId + "\""
                + ",\"impersonatorUserId\":" + impersonatorUserId
                + ",\"targetUserId\":" + targetUserId
                + ",\"startedAt\":\"2026-05-14T00:00:00Z\""
                + ",\"expiresAt\":\"2026-05-14T01:00:00Z\""
                + ",\"status\":\"ACTIVE\"}";
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/internal/impersonation/sessions/active"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body)));
    }

    // ------------------------------------------------------------------
    // Faz 2 case set — negative branches + revoke. Codex 019e2022.
    // ------------------------------------------------------------------

    @Test
    void target_user_disabled_emits_blocked_audit() throws Exception {
        // arrange — authz superAdmin true, user-service returns disabled
        stubAuthzMe(true);
        long targetUserId = 42L;
        String targetEmail = "disabled.user@example.com";
        stubUserServiceTarget(targetUserId, "any-uuid", targetEmail, /*enabled*/ false);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"Faz 2 disabled target case\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(403);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("errorCode").asText()).isEqualTo("TARGET_USER_DISABLED");

        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("eventType").asText()).isEqualTo("IMPERSONATION_BLOCKED");
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("TARGET_USER_DISABLED");
        assertThat(auditBody.get("targetEmail").asText()).isEqualTo(targetEmail);
        assertThat(auditBody.get("targetUserId").asLong()).isEqualTo(targetUserId);

        // No session create, no broker exchange
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());
    }

    @Test
    void target_subject_unresolvable_step1f_emits_blocked_audit() throws Exception {
        // BUG #1 Step 1f branch — kcSubject null on the user-service payload.
        stubAuthzMe(true);
        long targetUserId = 99L;
        String targetEmail = "no-kc-mapping@example.com";
        // null kcSubject — stubUserServiceTarget omits the field when null
        stubUserServiceTarget(targetUserId, /*kcSubject*/ null, targetEmail, /*enabled*/ true);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"Faz 2 BUG#1 Step 1f branch\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(422);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("errorCode").asText()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");

        // BUG #1 Step 1f proof — audit body carries the targetEmail
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("eventType").asText()).isEqualTo("IMPERSONATION_BLOCKED");
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");
        assertThat(auditBody.get("targetEmail").asText()).isEqualTo(targetEmail);
        assertThat(auditBody.get("targetUserId").asLong()).isEqualTo(targetUserId);

        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());
    }

    @Test
    void insufficient_authority_when_authz_returns_not_super_admin() throws Exception {
        // ImpersonationController resolves the target via user-service BEFORE
        // the SuperAdmin authority check (Step 2 lands after Step 1c/1f).
        // The contract pinned here: authz/me returning superAdmin=false ends
        // the chain BEFORE session create + broker exchange — user-service
        // may or may not be called depending on branch ordering.
        stubAuthzMe(false);
        stubUserServiceTarget(42L, "target-uuid", "halil@example.com", true);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "viewer@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":42,\"reason\":\"Faz 2 insufficient authority case\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(403);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("errorCode").asText()).isEqualTo("INSUFFICIENT_AUTHORITY");

        // The critical invariant — no session create + no broker exchange.
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());

        // Codex 019e2022 REVISE-3 pin: controller flow currently resolves
        // the target via user-service BEFORE the authority check, so the
        // user-service lookup IS exercised on this branch.
        assertThat(countGetsTo("/api/users/internal/42/impersonation-target")).isEqualTo(1);
    }

    @Test
    void active_session_conflict_returns_409_with_resolved_target_email_in_audit()
            throws Exception {
        // Full chain up to permission-service session create; that endpoint
        // returns 409 (single-active-session policy). Codex 019e2022 REVISE-3:
        // the 409 audit body must carry the targetEmail that was RESOLVED
        // from user-service, not whatever the request happened to include
        // (BUG #1 pattern). The request below intentionally omits
        // targetEmail to exercise that resolution path.
        stubAuthzMe(true);
        long targetUserId = 42L;
        String targetSubject = "target-uuid-409";
        String resolvedTargetEmail = "halil@example.com";
        stubUserServiceTarget(targetUserId, targetSubject, resolvedTargetEmail, true);
        stubSessionCreate409();
        stubAuditAccepted();

        String fakeJwt = TestTokens.buildFakeExchangedJwt(
                "https://wiremock-it/realms/test", "jti-409", "sid-409",
                targetSubject, "impersonation-broker", 9_999_999_999L, resolvedTargetEmail);
        KeycloakBrokerClient.DecodedClaims claims = new KeycloakBrokerClient.DecodedClaims(
                "https://wiremock-it/realms/test", "jti-409", "sid-409",
                targetSubject, "impersonation-broker", 9_999_999_999L, resolvedTargetEmail);
        when(keycloakBrokerClient.exchange(any(), eq(targetSubject)))
                .thenReturn(new KeycloakBrokerClient.ExchangeResult(fakeJwt, 3600L, claims));

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"Faz 2 concurrent 409 case\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(409);
        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(response.get("errorCode").asText()).isEqualTo("ACTIVE_IMPERSONATION_EXISTS");

        // 409 path runs the full chain up to session create — verify it.
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isEqualTo(1);

        // Codex 019e2022 REVISE-3: audit assertion catches the BUG #1
        // regression in the 409 branch.
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody)
                .as("409 conflict must still write an audit row")
                .isNotNull();
        assertThat(auditBody.get("eventType").asText()).isEqualTo("IMPERSONATION_BLOCKED");
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("ACTIVE_IMPERSONATION_EXISTS");
        assertThat(auditBody.get("targetUserId").asLong()).isEqualTo(targetUserId);
        assertThat(auditBody.get("targetEmail").asText())
                .as("BUG #1 regression — 409 branch must carry the resolved target email")
                .isEqualTo(resolvedTargetEmail);
    }

    @Test
    void revoke_active_session_returns_204() throws Exception {
        // DELETE /current flow:
        //   1. controller reads active session via
        //      GET /api/v1/internal/impersonation/sessions/active?impersonatorUserId={N}
        //   2. controller stops it via
        //      DELETE /api/v1/internal/impersonation/sessions/{id}
        String sessionId = "00000000-0000-0000-0000-000000000042";
        stubActiveSessionExists(sessionId, 1L, 42L);
        stubSessionStop204(sessionId);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete("/api/v1/impersonation/sessions/current")
                                .with(jwt().jwt(j -> j.subject("admin-uuid")
                                        .claim("userId", 1L)
                                        .claim("email", "admin@example.com")
                                        .claim("azp", "frontend"))))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(204);

        // Verify the controller hit the canonical DELETE path with the
        // session id resolved from the active lookup, plus the contract
        // headers Codex 019e2022 REVISE-3 asked us to pin.
        var stopEvents = wireMock.findAll(
                WireMock.deleteRequestedFor(urlPathEqualTo(
                        "/api/v1/internal/impersonation/sessions/" + sessionId)));
        assertThat(stopEvents)
                .as("permission-service DELETE on the resolved session id must be called")
                .isNotEmpty();
        var stopReq = stopEvents.get(0);
        assertThat(stopReq.getHeader("X-Stop-Reason"))
                .as("DELETE must carry X-Stop-Reason header (USER_STOP for /current)")
                .isEqualTo("USER_STOP");
        assertThat(stopReq.getHeader("X-Internal-Api-Key"))
                .as("DELETE must carry the internal API key")
                .isNotEmpty();

        // GET /active must be called with impersonatorUserId query param.
        var activeEvents = wireMock.findAll(
                WireMock.getRequestedFor(urlPathEqualTo(
                        "/api/v1/internal/impersonation/sessions/active")));
        assertThat(activeEvents)
                .as("controller must look up the active session before stopping it")
                .isNotEmpty();
        assertThat(activeEvents.get(0).getUrl())
                .as("active lookup must carry impersonatorUserId=1 query param")
                .contains("impersonatorUserId=1");
    }

    // ------------------------------------------------------------------
    // Audit target_email global invariant tests (Codex 019e27bf
    // fresh-context audit finding #4 — 9+ branches had been left using
    // request.targetEmail(); the controller now routes them through the
    // auditTargetEmail helper so every BLOCKED/FAILED row carries the
    // resolved address even when the operator omits it from the body).
    //
    // These cases POST without targetEmail and assert the audit row
    // still carries the user-service-resolved address. Each one drives
    // the controller to a different post-resolution branch.
    // ------------------------------------------------------------------

    @Test
    void post_resolution_disabled_branch_audit_carries_resolved_email() throws Exception {
        long targetUserId = 50L;
        String resolvedEmail = "disabled.target@example.com";
        stubAuthzMe(true);
        stubUserServiceTarget(targetUserId, "target-disabled-uuid", resolvedEmail, false);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"disabled branch audit invariant\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(403);
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("TARGET_USER_DISABLED");
        assertThat(auditBody.get("targetEmail").asText())
                .as("DISABLED branch must carry the user-service-resolved email")
                .isEqualTo(resolvedEmail);
    }

    @Test
    void post_resolution_insufficient_authority_branch_audit_carries_resolved_email()
            throws Exception {
        long targetUserId = 51L;
        String resolvedEmail = "halil@example.com";
        // authz/me says not super admin; target resolution still runs.
        stubAuthzMe(false);
        stubUserServiceTarget(targetUserId, "target-not-self", resolvedEmail, true);
        stubAuditAccepted();

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "viewer@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"insufficient authority invariant\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(403);
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("INSUFFICIENT_AUTHORITY");
        assertThat(auditBody.get("targetEmail").asText())
                .as("INSUFFICIENT_AUTHORITY branch must carry resolved email")
                .isEqualTo(resolvedEmail);
    }

    @Test
    void post_exchange_subject_mismatch_branch_audit_carries_claims_email()
            throws Exception {
        long targetUserId = 52L;
        String targetSubject = "target-mismatch-uuid";
        String resolvedEmail = "halil.kocoglu@example.com";
        String claimsEmail = "claims-email-from-kc@example.com"; // KC-authoritative
        stubAuthzMe(true);
        stubUserServiceTarget(targetUserId, targetSubject, resolvedEmail, true);
        stubSessionCreate201(java.util.UUID.randomUUID().toString(), 1L, targetUserId);
        stubAuditAccepted();

        // Force the controller's Step 3a subject-mismatch guard: KC
        // returns claims with a DIFFERENT sub than the one we asked for.
        String fakeJwt = TestTokens.buildFakeExchangedJwt(
                "https://wiremock-it/realms/test",
                "jti-mismatch",
                "sid-mismatch",
                "different-subject-from-kc", // <-- mismatch
                "impersonation-broker",
                9_999_999_999L,
                claimsEmail);
        KeycloakBrokerClient.DecodedClaims claims = new KeycloakBrokerClient.DecodedClaims(
                "https://wiremock-it/realms/test",
                "jti-mismatch",
                "sid-mismatch",
                "different-subject-from-kc",
                "impersonation-broker",
                9_999_999_999L,
                claimsEmail);
        when(keycloakBrokerClient.exchange(any(), eq(targetSubject)))
                .thenReturn(new KeycloakBrokerClient.ExchangeResult(fakeJwt, 3600L, claims));

        var mvcResult = mockMvc.perform(post("/api/v1/impersonation/sessions")
                        .with(jwt().jwt(j -> j.subject("admin-uuid")
                                .claim("userId", 1L)
                                .claim("email", "admin@example.com")
                                .claim("azp", "frontend")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetUserId
                                + ",\"reason\":\"subject mismatch invariant\"}"))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(400);
        JsonNode auditBody = lastBodyTo("/api/v1/internal/impersonation/audit-events");
        assertThat(auditBody).isNotNull();
        assertThat(auditBody.get("errorCode").asText()).isEqualTo("TARGET_SUBJECT_MISMATCH");
        // Post-exchange branches prefer claims.email (KC authoritative).
        assertThat(auditBody.get("targetEmail").asText())
                .as("post-exchange branch must carry claims.email (KC-authoritative)")
                .isEqualTo(claimsEmail);
    }
}
