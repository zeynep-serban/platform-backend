package com.serban.notify.api;

import com.serban.notify.config.SecurityConfig;
import com.serban.notify.inbox.InboxService;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InboxController + InboxSseController identity match security test
 * (Faz 23.4 PR-E.5).
 *
 * <p>Verifies that {@link SubscriberIdentityGuard} blocks requests where
 * the {@code X-Subscriber-Id} header (REST) or {@code subscriberId} query
 * param (SSE) does not match the JWT principal's {@code sub} claim. Runs
 * under {@code security-test} profile so the real {@code SecurityConfig}
 * filter chain is wired (the {@code !local & !test} guard).
 *
 * <p>Why a dedicated test class: existing {@code InboxControllerTest} and
 * {@code InboxSseControllerSliceTest} run with {@code addFilters=false}
 * to keep the slice fast; the guard's silent-pass branch means those
 * tests don't exercise identity enforcement at all. This class fills the
 * coverage gap.
 */
@WebMvcTest(controllers = {InboxController.class, InboxSseController.class},
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({SecurityConfig.class, InboxControllerSecurityTest.SecurityTestConfig.class})
@ActiveProfiles("security-test")
class InboxControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockBean InboxService inboxService;

    // ─── REST endpoints ──────────────────────────────────────────────────

    @Test
    void listMine_jwtSubMatchesHeader_returns200() throws Exception {
        // Service may be invoked with empty results; mock returns null page
        // path is fine because we only assert the security boundary.
        // Actual service-call test is in InboxControllerTest (slice).
        org.mockito.Mockito.when(inboxService.listActive(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "alice"))
            .andExpect(status().isOk());
    }

    @Test
    void listMine_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    @Test
    void unreadCount_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me/unread-count")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    @Test
    void markAsRead_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/notify/inbox/42/read")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    @Test
    void archive_jwtSubMismatchesHeader_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/notify/inbox/42/archive")
                .with(jwt().jwt(j -> j.subject("alice")))
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "bob"))
            .andExpect(status().isForbidden());
    }

    // ─── SSE endpoint ────────────────────────────────────────────────────

    @Test
    void sseStream_jwtSubMismatchesQueryParam_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .with(jwt().jwt(j -> j.subject("alice")))
                .param("orgId", "default")
                .param("subscriberId", "bob")
                .accept("text/event-stream"))
            .andExpect(status().isForbidden());
    }

    @Test
    void sseStream_jwtSubMatchesQueryParam_startsAsync() throws Exception {
        // Match → SSE pipeline starts (asyncStarted). Service unread count
        // is irrelevant to the boundary; mock returns 0 to avoid NPE.
        org.mockito.Mockito.when(inboxService.unreadCount(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(0L);

        mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .with(jwt().jwt(j -> j.subject("alice")))
                .param("orgId", "default")
                .param("subscriberId", "alice")
                .accept("text/event-stream"))
            .andExpect(status().isOk());
    }

    /**
     * Stub JwtDecoder + real {@link SubscriberIdentityGuard} bean.
     * SecurityConfig pulls in JwtDecoder for the resource-server filter
     * chain; we override with a stub that throws on actual decode (we use
     * Spring's {@code .with(jwt())} test post-processor instead).
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
            return new SubscriberIdentityGuard();
        }
    }
}
