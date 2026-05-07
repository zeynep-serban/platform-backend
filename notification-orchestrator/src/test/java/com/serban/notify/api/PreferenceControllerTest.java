package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.preference.SubscriberPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PreferenceController @WebMvcTest slice (Faz 23.5 PR2).
 *
 * <p>Test scope:
 * <ul>
 *   <li>GET /me — list rows + identity headers required</li>
 *   <li>PUT /me — upsert + service invocation with the right tuple</li>
 *   <li>DELETE /me/{id} — 204 happy path / 404 when service returns false</li>
 *   <li>Feature flag — see PreferenceControllerDisabledTest</li>
 * </ul>
 *
 * <p>Runs under the {@code test} profile so {@code SecurityConfigTest}
 * permissive chain applies (matches the InboxControllerTest pattern;
 * identity-match enforcement is covered by
 * {@link InboxControllerSecurityTest} which uses the real SecurityConfig).
 */
@WebMvcTest(controllers = PreferenceController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = "notify.preferences.enabled=true")
class PreferenceControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean SubscriberPreferenceService preferenceService;

    @Test
    void listMineReturns200WithRows() throws Exception {
        when(preferenceService.listForSubscriber("default", "sub-1"))
            .thenReturn(List.of(stub(7L, "report.export.ready", "email", true)));

        mockMvc.perform(get("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].topicKey").value("report.export.ready"))
            .andExpect(jsonPath("$[0].channel").value("email"))
            .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void listMineWithoutHeadersReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/preferences/me"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void upsertMineReturns200WithSavedRow() throws Exception {
        when(preferenceService.upsert(
            eq("default"), eq("sub-1"),
            eq("system.maintenance"), eq("email"),
            eq(false), any(), eq(0), eq(true)
        )).thenReturn(stub(99L, "system.maintenance", "email", false));

        String body = objectMapper.writeValueAsString(Map.of(
            "topicKey", "system.maintenance",
            "channel", "email",
            "enabled", false,
            "quietHours", Map.of("start", "22:00", "end", "07:00"),
            "frequencyLimitPerDay", 0,
            "bypassForCritical", true
        ));

        mockMvc.perform(put("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(99))
            .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void upsertMineWithMinimalBodyAcceptsAllNullsAndDefaults() throws Exception {
        // Codex iter P1/P2 absorb: enabled is now @NotNull on the DTO,
        // so the caller MUST send it explicitly. With just "enabled":false
        // the upsert creates a both-null wildcard "mute all" row.
        when(preferenceService.upsert(
            eq("default"), eq("sub-1"),
            eq(null), eq(null),
            eq(false), any(), eq(null), eq(null)
        )).thenReturn(stub(101L, null, null, false));

        mockMvc.perform(put("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(101))
            .andExpect(jsonPath("$.topicKey").doesNotExist())
            .andExpect(jsonPath("$.channel").doesNotExist());
    }

    @Test
    void upsertMineWithMissingEnabledReturns400() throws Exception {
        // Codex iter P1/P2 absorb: the DTO @NotNull on `enabled`
        // surfaces a frontend payload bug as a 400 instead of a silent
        // unintended mute (primitive default would have been false).
        mockMvc.perform(put("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicKey\":\"system.maintenance\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deleteMineReturns204OnHappyPath() throws Exception {
        when(preferenceService.delete("default", "sub-1", 42L)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/notify/preferences/me/42")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteMineReturns404WhenServiceReturnsFalse() throws Exception {
        when(preferenceService.delete(anyString(), anyString(), anyLong())).thenReturn(false);

        mockMvc.perform(delete("/api/v1/notify/preferences/me/9999")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isNotFound());
    }

    // ── Faz 23.6 PR-A1 — DELETE /me restore-defaults ──────────────────────

    @Test
    void deleteAllMine_returns200WithDeletedCount() throws Exception {
        when(preferenceService.restoreDefaults("default", "sub-1")).thenReturn(3);

        mockMvc.perform(delete("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedCount").value(3));
    }

    @Test
    void deleteAllMine_zeroRows_idempotentReturns200() throws Exception {
        // Idempotent contract: a follow-up call with no rows still returns
        // 200 + count=0 so retry/UX behaviour stays sane.
        when(preferenceService.restoreDefaults("default", "sub-1")).thenReturn(0);

        mockMvc.perform(delete("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedCount").value(0));
    }

    // ── Faz 23.6 PR-A2 — POST /me/mute-channel ────────────────────────────

    @Test
    void muteChannelReturns200WithDeletedOverrideAndShadowDenyCounts() throws Exception {
        when(preferenceService.muteChannel("default", "sub-1", "email"))
            .thenReturn(new com.serban.notify.preference.SubscriberPreferenceService
                .MuteChannelResult(3, 2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/notify/preferences/me/mute-channel")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"email\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channel").value("email"))
            .andExpect(jsonPath("$.muted").value(true))
            .andExpect(jsonPath("$.deletedOverrideCount").value(3))
            .andExpect(jsonPath("$.shadowDenyCount").value(2));
    }

    @Test
    void muteChannelRejectsUnknownChannelWith400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/notify/preferences/me/mute-channel")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"smoke-signal\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void muteChannelRejectsBlankChannelWith400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/notify/preferences/me/mute-channel")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    private static SubscriberPreference stub(
        Long id, String topicKey, String channel, boolean enabled
    ) {
        SubscriberPreference p = new SubscriberPreference();
        p.setId(id);
        p.setOrgId("default");
        p.setSubscriberId("sub-1");
        p.setTopicKey(topicKey);
        p.setChannel(channel);
        p.setEnabled(enabled);
        OffsetDateTime now = OffsetDateTime.now();
        p.setQuietHours(null);
        p.setFrequencyLimitPerDay(null);
        p.setBypassForCritical(true);
        // createdAt/updatedAt cannot be set via setter; rely on @PrePersist
        // in real flow. For the slice test the stub doesn't need them.
        return p;
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in PreferenceControllerTest");
            };
        }

        @Bean
        public SubscriberIdentityGuard subscriberIdentityGuard() {
            return SubscriberIdentityGuardTestSupport.newGuard();
        }

        @Bean
        public NotifyOrgAccessGuard notifyOrgAccessGuard() {
            // Faz 23.6 PR-A1: PreferenceController gains the org guard
            // alongside the subscriber guard. Tests run with no
            // SecurityContext, so the guard's silent-pass branch
            // applies and the existing assertions remain valid.
            return NotifyOrgAccessGuardTestSupport.newGuard();
        }
    }
}
