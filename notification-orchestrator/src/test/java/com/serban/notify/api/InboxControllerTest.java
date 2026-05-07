package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.inbox.InboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * InboxController @WebMvcTest slice (Faz 23.3 PR-E.1).
 *
 * <p>Test scope:
 * <ul>
 *   <li>GET /api/v1/notify/inbox/me — paged listing + unread count in body</li>
 *   <li>GET /api/v1/notify/inbox/me/unread-count — lightweight badge endpoint</li>
 *   <li>POST /api/v1/notify/inbox/{id}/read — happy path + 404 cross-tenant</li>
 *   <li>POST /api/v1/notify/inbox/{id}/archive — happy path + 404 missing</li>
 *   <li>Header validation — X-Org-Id + X-Subscriber-Id required</li>
 *   <li>Pagination defaults + clamps</li>
 * </ul>
 *
 * <p>Runs under {@code test} profile so {@code SecurityConfigTest} permissive
 * chain applies (matches IntentController pattern; auth covered separately).
 */
@WebMvcTest(controllers = InboxController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class InboxControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean InboxService inboxService;

    // ─── GET /me listing ─────────────────────────────────────────────────

    @Test
    void listMineReturns200WithItemsAndUnreadCount() throws Exception {
        NotificationInbox row = stubRow();
        Page<NotificationInbox> page = new PageImpl<>(List.of(row), Pageable.ofSize(20), 1);
        when(inboxService.listActive(anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(page);
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(3L);

        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(42))
            .andExpect(jsonPath("$.items[0].state").value("UNREAD"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.unreadCount").value(3));
    }

    @Test
    void listMineWithoutOrgIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listMineWithoutSubscriberIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Org-Id", "default"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listMineRespectsPageAndSizeQuery() throws Exception {
        when(inboxService.listActive(anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(Page.empty());
        when(inboxService.unreadCount(anyString(), anyString())).thenReturn(0L);

        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .param("page", "2")
                .param("size", "5"))
            .andExpect(status().isOk());
    }

    @Test
    void listMineNegativePageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .param("page", "-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listMineZeroSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/inbox/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .param("size", "0"))
            .andExpect(status().isBadRequest());
    }

    // ─── GET /me/unread-count ───────────────────────────────────────────

    @Test
    void unreadCountReturnsLightweightResponse() throws Exception {
        when(inboxService.unreadCount("default", "sub-1")).thenReturn(7L);

        mockMvc.perform(get("/api/v1/notify/inbox/me/unread-count")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(7));
    }

    // ─── POST /{id}/read ────────────────────────────────────────────────

    @Test
    void markAsReadHappyPathReturns200() throws Exception {
        NotificationInbox readRow = stubRow();
        readRow.setState(NotificationInbox.State.READ);
        readRow.setReadAt(OffsetDateTime.now());
        when(inboxService.markAsRead("default", 42L, "sub-1"))
            .thenReturn(Optional.of(readRow));

        mockMvc.perform(post("/api/v1/notify/inbox/42/read")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.state").value("READ"))
            .andExpect(jsonPath("$.readAt").exists());
    }

    @Test
    void markAsReadCrossTenantReturns404() throws Exception {
        when(inboxService.markAsRead("default", 42L, "wrong-sub"))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/notify/inbox/42/read")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "wrong-sub"))
            .andExpect(status().isNotFound());
    }

    @Test
    void markAsReadNonexistentIdReturns404() throws Exception {
        when(inboxService.markAsRead(anyString(), anyLong(), anyString()))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/notify/inbox/9999/read")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isNotFound());
    }

    // ─── POST /{id}/archive ─────────────────────────────────────────────

    @Test
    void archiveHappyPathReturns200() throws Exception {
        NotificationInbox archivedRow = stubRow();
        archivedRow.setState(NotificationInbox.State.ARCHIVED);
        archivedRow.setArchivedAt(OffsetDateTime.now());
        when(inboxService.archive("default", 42L, "sub-1"))
            .thenReturn(Optional.of(archivedRow));

        mockMvc.perform(post("/api/v1/notify/inbox/42/archive")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.state").value("ARCHIVED"))
            .andExpect(jsonPath("$.archivedAt").exists());
    }

    @Test
    void archiveNonexistentIdReturns404() throws Exception {
        when(inboxService.archive(anyString(), anyLong(), anyString()))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/notify/inbox/9999/archive")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void archiveWithoutHeadersReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/notify/inbox/42/archive"))
            .andExpect(status().isBadRequest());
    }

    // ─── Faz 23.5 PR1: bulk mark-all-read ────────────────────────────────

    @Test
    void markAllAsReadReturns200WithUpdatedCount() throws Exception {
        // Faz 23.5 hardening (Codex 019e03b5): cutoff is now DB-sourced
        // so the controller no longer passes it. The service mock returns
        // a fixed timestamp the same way the production service would
        // forward repository.currentDatabaseTimestamp().
        OffsetDateTime dbCutoff = OffsetDateTime.parse("2026-05-07T12:00:00Z");
        when(inboxService.markAllAsRead(anyString(), anyString()))
            .thenReturn(new InboxService.BulkMarkAllReadResult(7, dbCutoff));

        mockMvc.perform(post("/api/v1/notify/inbox/me/mark-all-read")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updatedCount").value(7))
            .andExpect(jsonPath("$.cutoff").exists());
    }

    @Test
    void markAllAsReadIdempotentReturnsZeroCount() throws Exception {
        OffsetDateTime dbCutoff = OffsetDateTime.parse("2026-05-07T12:00:00Z");
        when(inboxService.markAllAsRead(anyString(), anyString()))
            .thenReturn(new InboxService.BulkMarkAllReadResult(0, dbCutoff));

        mockMvc.perform(post("/api/v1/notify/inbox/me/mark-all-read")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updatedCount").value(0));
    }

    @Test
    void markAllAsReadWithoutOrgIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/notify/inbox/me/mark-all-read")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void markAllAsReadWithoutSubscriberIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/notify/inbox/me/mark-all-read")
                .header("X-Org-Id", "default"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Stub JwtDecoder bean — bypass real KC JWKS network fetch.
     * Test profile uses permissive SecurityConfigTest, so JwtDecoder
     * isn't actually invoked, but slice context creation needs the bean.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in InboxControllerTest");
            };
        }

        /**
         * Faz 23.4 PR-E.5: real {@link SubscriberIdentityGuard} bean.
         * Slice tests run with {@code addFilters=false}; SecurityContext
         * is empty so the guard returns silently — same code path as
         * permissive non-prod profiles. Identity match enforcement is
         * exercised in {@code InboxControllerSecurityTest} which keeps
         * filters enabled and supplies a real JWT mock.
         */
        @Bean
        public SubscriberIdentityGuard subscriberIdentityGuard() {
            return SubscriberIdentityGuardTestSupport.newGuard();
        }
    }

    private static NotificationInbox stubRow() {
        NotificationInbox row = new NotificationInbox();
        row.setId(42L);
        row.setIntentId("intent-x");
        row.setOrgId("default");
        row.setSubscriberId("sub-1");
        row.setSubject("Hello");
        row.setBodyText("body");
        row.setLocale("tr-TR");
        row.setTopicKey("auth.password-reset");
        row.setSeverity("info");
        row.setState(NotificationInbox.State.UNREAD);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }
}
