package com.serban.notify.api;

import com.serban.notify.api.dto.AuditHistoryListResponse;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.erasure.ErasureService;
import com.serban.notify.erasure.SubscriberErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SubscriberErasureController security boundary test (Faz 23.2.B M3 stale
 * audit closure path — Codex thread {@code 019e0c28} P1 absorb: controller
 * security tests required for new protected endpoint).
 *
 * <p>Verifies:
 * <ul>
 *   <li>JWT sub claim matches X-Subscriber-Id header → 200 OK + service
 *       called</li>
 *   <li>JWT sub claim mismatches X-Subscriber-Id → 403 Forbidden + service
 *       NEVER called (defense-in-depth: identity guard rejects before
 *       reaching service layer)</li>
 *   <li>Missing X-Org-Id or X-Subscriber-Id → 400 Bad Request</li>
 *   <li>DELETE response uses self-service evidence_ref + counters</li>
 *   <li>No free-form reason query param accepted (PII boundary, Codex P1
 *       absorb)</li>
 * </ul>
 */
@WebMvcTest(controllers = SubscriberErasureController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({SecurityConfig.class, SubscriberErasureControllerSecurityTest.SecurityTestConfig.class})
@ActiveProfiles("security-test")
class SubscriberErasureControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockBean SubscriberErasureService subscriberErasureService;

    // ─── GET /audit/me ──────────────────────────────────────────────────

    @Test
    void listMyAudit_jwtSubMatchesHeader_returns200_callsService() throws Exception {
        when(subscriberErasureService.listMyAudit(eq("default"), eq("alice"), anyInt(), anyInt()))
            .thenReturn(new AuditHistoryListResponse(List.of(), 0L, 0, 20));

        mockMvc.perform(get("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isOk());

        verify(subscriberErasureService).listMyAudit(eq("default"), eq("alice"), anyInt(), anyInt());
    }

    @Test
    void listMyAudit_jwtSubMismatchesHeader_returns403_serviceNeverCalled() throws Exception {
        mockMvc.perform(get("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());

        verify(subscriberErasureService, never()).listMyAudit(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void listMyAudit_missingOrgIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isBadRequest());

        verify(subscriberErasureService, never()).listMyAudit(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void listMyAudit_missingSubscriberIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default"))
            .andExpect(status().isBadRequest());

        verify(subscriberErasureService, never()).listMyAudit(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void listMyAudit_anonymousNoJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notify/audit/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isUnauthorized());

        verify(subscriberErasureService, never()).listMyAudit(anyString(), anyString(), anyInt(), anyInt());
    }

    // ─── DELETE /audit/me ───────────────────────────────────────────────

    @Test
    void eraseMyAudit_jwtSubMatchesHeader_returns200_evidenceRefAndCounters() throws Exception {
        when(subscriberErasureService.eraseMyAudit(eq("default"), eq("alice")))
            .thenReturn(new ErasureService.EraseResult(2, 5, 1));

        MvcResult result = mockMvc.perform(delete("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evidence_ref").value("self-service-kvkk-art-11"))
            .andExpect(jsonPath("$.intents_erased").value(2))
            .andExpect(jsonPath("$.deliveries_anonymized").value(5))
            .andExpect(jsonPath("$.inbox_rows_deleted").value(1))
            .andExpect(jsonPath("$.status").value("completed"))
            .andReturn();

        // Codex P1 absorb: response body'de yalnız sabit evidence_ref;
        // user-provided reason yok.
        assertThat(result.getResponse().getContentAsString())
            .contains("self-service-kvkk-art-11");

        verify(subscriberErasureService).eraseMyAudit(eq("default"), eq("alice"));
    }

    @Test
    void eraseMyAudit_jwtSubMismatchesHeader_returns403_serviceNeverCalled() throws Exception {
        mockMvc.perform(delete("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());

        verify(subscriberErasureService, never()).eraseMyAudit(anyString(), anyString());
    }

    @Test
    void eraseMyAudit_missingHeaders_returns400() throws Exception {
        mockMvc.perform(delete("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice"))))
            .andExpect(status().isBadRequest());

        verify(subscriberErasureService, never()).eraseMyAudit(anyString(), anyString());
    }

    @Test
    void eraseMyAudit_anonymousNoJwt_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/notify/audit/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isUnauthorized());

        verify(subscriberErasureService, never()).eraseMyAudit(anyString(), anyString());
    }

    @Test
    void eraseMyAudit_noOpResponse_statusIsNoOp() throws Exception {
        when(subscriberErasureService.eraseMyAudit(any(), any()))
            .thenReturn(new ErasureService.EraseResult(0, 0, 0));

        mockMvc.perform(delete("/api/v1/notify/audit/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("no_op"))
            .andExpect(jsonPath("$.evidence_ref").value("self-service-kvkk-art-11"));
    }

    /**
     * Stub JwtDecoder bean + real Subscriber/Org guard beans (Inbox security
     * test pattern reuse). Tests use {@code SecurityMockMvcRequestPostProcessors.jwt()}
     * so JwtDecoder isn't exercised.
     */
    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised — tests use SecurityMockMvcRequestPostProcessors.jwt()");
            };
        }

        @Bean
        public SubscriberIdentityGuard subscriberIdentityGuard() {
            return SubscriberIdentityGuardTestSupport.newGuard();
        }

        @Bean
        public NotifyOrgAccessGuard notifyOrgAccessGuard() {
            return NotifyOrgAccessGuardTestSupport.newGuard();
        }
    }
}
