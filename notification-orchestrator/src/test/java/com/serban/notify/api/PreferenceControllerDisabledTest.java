package com.serban.notify.api;

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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the preference controller fail-closes the entire
 * surface when {@code notify.preferences.enabled=false}
 * (Faz 23.5 PR2).
 *
 * <p>Separate slice (not @TestPropertySource on the main test class)
 * because the gate is wired at construction time via @Value; flipping
 * the property between tests in the same context would not rebuild
 * the controller. Two slices keep both behaviors covered cleanly.
 */
@WebMvcTest(controllers = PreferenceController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = "notify.preferences.enabled=false")
class PreferenceControllerDisabledTest {

    @Autowired MockMvc mockMvc;

    @MockBean SubscriberPreferenceService preferenceService;

    @Test
    void listMineReturns503AndDoesNotCallService() throws Exception {
        mockMvc.perform(get("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("preferences_disabled"));

        verify(preferenceService, never()).listForSubscriber(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void upsertMineReturns503() throws Exception {
        mockMvc.perform(put("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deleteMineReturns503() throws Exception {
        mockMvc.perform(delete("/api/v1/notify/preferences/me/42")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deleteAllMineReturns503AndDoesNotCallService() throws Exception {
        // Faz 23.6 PR-A1: feature gate also covers the bulk
        // restore-defaults endpoint.
        mockMvc.perform(delete("/api/v1/notify/preferences/me")
                .header("X-Org-Id", "default")
                .header("X-Subscriber-Id", "sub-1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("preferences_disabled"));

        verify(preferenceService, never()).restoreDefaults(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in PreferenceControllerDisabledTest");
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
