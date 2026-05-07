package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.preference.SubscriberPreferenceService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Identity-match security test for {@link PreferenceController}
 * (Faz 23.5 PR2 + Faz 23.5 hardening — Codex thread {@code 019e0316}
 * iter-3 absorb).
 *
 * <p>Pattern mirrors {@code InboxControllerSecurityTest}: real
 * {@link SecurityConfig} filter chain, real {@link SubscriberIdentityGuard}
 * bean, the {@code jwt()} post-processor sets a JWT principal whose
 * {@code sub} claim either matches or mismatches the
 * {@code X-Subscriber-Id} header. Each {@code /me} endpoint MUST 403 on
 * mismatch and 200 on match.
 *
 * <p>Why this test gap matters: the existing slice tests
 * ({@link PreferenceControllerTest},
 * {@link PreferenceControllerDisabledTest}) run with
 * {@code addFilters=false}, so the guard's silent-pass branch hides the
 * boundary. This file exercises the security path end-to-end.
 */
@WebMvcTest(controllers = PreferenceController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban",
        "notify.preferences.enabled=true"
    })
@Import({SecurityConfig.class, PreferenceControllerSecurityTest.SecurityTestConfig.class})
@ActiveProfiles("security-test")
class PreferenceControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean SubscriberPreferenceService preferenceService;

    @Test
    void list_jwtSubMatchesHeader_returns200() throws Exception {
        org.mockito.Mockito.when(preferenceService.listForSubscriber(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/v1/notify/preferences/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void list_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/preferences/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    @Test
    void upsert_jwtSubMismatchesHeader_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "topicKey", "auth.password-reset",
            "channel", "email",
            "enabled", false
        ));

        mockMvc.perform(put("/api/v1/notify/preferences/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    @Test
    void delete_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/notify/preferences/me/42")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

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
    }
}
