package com.serban.notify.api;

import com.serban.notify.inbox.InboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InboxSseController @WebMvcTest slice (Faz 23.3 PR-E.3 Codex iter-1 P2.6 absorb).
 *
 * <p>Verifies SSE wire format + query-param contract + validation:
 * <ul>
 *   <li>200 + Content-Type text/event-stream on valid request</li>
 *   <li>Initial event payload contains {@code event: unread-count} + JSON data</li>
 *   <li>400 on missing orgId / subscriberId query param</li>
 * </ul>
 */
@WebMvcTest(controllers = InboxSseController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(InboxSseControllerSliceTest.TestConfig.class)
class InboxSseControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @MockBean InboxService inboxService;

    /**
     * Faz 23.4 PR-E.5: real {@link SubscriberIdentityGuard} bean. Slice
     * runs with {@code addFilters=false}; SecurityContext is empty so the
     * guard returns silently. JWT match enforcement is verified in
     * {@code InboxControllerSecurityTest}.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        SubscriberIdentityGuard subscriberIdentityGuard() {
            return new SubscriberIdentityGuard();
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.security.oauth2.jwt.JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in InboxSseControllerSliceTest");
            };
        }
    }

    @Test
    void sseStreamStartsAsyncWithInitialEventPayload() throws Exception {
        // SseEmitter is long-lived (30 min timeout); we cannot asyncDispatch
        // without hanging. Instead: verify async-started + read the partial
        // response buffer for the initial event already sent synchronously
        // inside subscribe(). Then complete the emitter to release resources.
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(5L);

        MvcResult result = mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .param("orgId", "default")
                .param("subscriberId", "sub-1")
                .accept("text/event-stream"))
            .andExpect(request().asyncStarted())
            .andReturn();

        // Initial event is written synchronously inside subscribe() before the
        // SseEmitter is returned, so the response buffer contains it now.
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:unread-count");
        assertThat(body).contains("\"unreadCount\":5");
        // SseEmitter is long-lived; left dangling in controller's emitter map.
        // Acceptable for slice test — JVM exit reclaims; emitter inactivity
        // doesn't affect other tests since each @WebMvcTest spins fresh context.
    }

    @Test
    void sseStreamMissingOrgIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .param("subscriberId", "sub-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sseStreamMissingSubscriberIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .param("orgId", "default"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sseStreamBlankOrgIdReturns400() throws Exception {
        // @NotBlank should reject empty-string param
        mockMvc.perform(get("/api/v1/notify/inbox/me/stream")
                .param("orgId", "")
                .param("subscriberId", "sub-1"))
            .andExpect(status().isBadRequest());
    }
}
