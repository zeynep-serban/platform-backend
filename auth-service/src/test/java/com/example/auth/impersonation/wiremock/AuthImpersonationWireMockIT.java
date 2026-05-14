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

        // assert zero downstream calls
        assertThat(countGetsTo("/api/users/internal/42/impersonation-target")).isZero();
        assertThat(countPostsTo("/api/v1/internal/impersonation/sessions")).isZero();
        verify(keycloakBrokerClient, never()).exchange(any(), any());
    }
}
