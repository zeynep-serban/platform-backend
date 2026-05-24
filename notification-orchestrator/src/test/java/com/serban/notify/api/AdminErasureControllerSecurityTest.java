package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.erasure.ErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminErasureController security gate test (Faz 23.2 PR-D.3.x).
 *
 * <p>Verifies @PreAuthorize ROLE_PRIVACY_OFFICER enforcement via @WebMvcTest
 * slice (no JPA, no DB). SecurityConfig active via {@code !local & !test}
 * profile guard — test runs under "security-test" profile.
 */
@WebMvcTest(controllers = AdminErasureController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({SecurityConfig.class, AdminErasureControllerSecurityTest.SecurityTestConfig.class})
@ActiveProfiles("security-test")  // !local & !test → SecurityConfig active
class AdminErasureControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ErasureService erasureService;
    @MockBean NotifyOrgAccessGuard orgAccessGuard;
    @MockBean com.serban.notify.authz.DpoAuthzService dpoAuthzService;
    @MockBean com.serban.notify.authz.DpoUserIdResolver dpoUserIdResolver;

    /**
     * K6 default mock: DPO authz allows everything. This preserves
     * the pre-K6 security-test invariant (legacy ROLE_PRIVACY_OFFICER
     * + orgAccessGuard stack is what the test is exercising). Tests
     * specific to K6 enforcement live in
     * {@code AdminErasureControllerK6DpoAuthzTest}.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUpDpoAuthzDefault() {
        when(dpoAuthzService.canEraseForOrg(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);
    }

    @Test
    @WithAnonymousUser
    void anonymousErasureRequest_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "org_id", "default",
            "subscriber_id", "1204",
            "reason", "test",
            "evidence_ref", "TICKET-1"
        ));

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_USER"})
    void authenticatedWithoutRole_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "org_id", "default",
            "subscriber_id", "1204",
            "reason", "test",
            "evidence_ref", "TICKET-1"
        ));

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void privacyOfficerRole_returns200_callsErasureService() throws Exception {
        when(erasureService.eraseSubscriber(any())).thenReturn(
            new ErasureService.EraseResult(1, 2, 0)
        );

        String body = objectMapper.writeValueAsString(Map.of(
            "org_id", "default",
            "subscriber_id", "1204",
            "reason", "GDPR Art 17",
            "evidence_ref", "TICKET-DPO-2026-001"
        ));

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER", "ROLE_OTHER"})
    void privacyOfficerWithExtraRoles_stillPasses() throws Exception {
        when(erasureService.eraseSubscriber(any())).thenReturn(
            new ErasureService.EraseResult(0, 0, 0)
        );

        String body = objectMapper.writeValueAsString(Map.of(
            "org_id", "default",
            "subscriber_id", "noop-subscriber",
            "reason", "smoke",
            "evidence_ref", "SMOKE-1"
        ));

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void crossOrgErasureRequest_returns403_orgGuardDenies() throws Exception {
        // Codex 019e4950 P1 #6 absorb: tenant-scoped DPO authz.
        // DPO/legal sadece kendi org'unun verisini silebilmeli;
        // cross-org çağrı NotifyOrgAccessGuard tarafından 403 dönmeli.
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException(
            "JWT trusted org set does not include other-tenant"
        )).when(orgAccessGuard).requireOrgAccessOrThrow(org.mockito.ArgumentMatchers.eq("other-tenant"));

        String body = objectMapper.writeValueAsString(Map.of(
            "org_id", "other-tenant",
            "subscriber_id", "1204",
            "reason", "subject_request",
            "evidence_ref", "TICKET-DPO-2026-002"
        ));

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        // ErasureService hiç çağrılmadı (guard önce throw)
        org.mockito.Mockito.verify(erasureService, org.mockito.Mockito.never())
            .eraseSubscriber(any());
    }

    /**
     * Stub JwtDecoder bean — bypass real KC JWKS network fetch.
     * @WithMockUser provides Authentication directly; decoder isn't exercised.
     */
    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in @WithMockUser tests");
            };
        }
    }
}
