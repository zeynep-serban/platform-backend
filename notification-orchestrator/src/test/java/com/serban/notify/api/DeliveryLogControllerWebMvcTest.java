package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.api.dto.DeliveryLogListResponse;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.service.DeliveryLogService;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security slice + auth boundary tests for {@link DeliveryLogController}
 * (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: org-boundary tests
 * use the {@code jwt()} postprocessor (real {@code JwtAuthenticationToken} +
 * claims). {@code @WithMockUser} would not exercise the JWT claim path.
 */
@WebMvcTest(controllers = DeliveryLogController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({
    SecurityConfig.class,
    NotifyOrgAccessGuard.class,
    DeliveryLogControllerWebMvcTest.NotifyConfigStub.class,
    DeliveryLogControllerWebMvcTest.JwtDecoderStub.class
})
@ActiveProfiles("security-test")  // !local & !test → SecurityConfig active
class DeliveryLogControllerWebMvcTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DeliveryLogService deliveryLogService;

    @Test
    void anonymousRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "default")
                .with(anonymous()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAuthority_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void missingXOrgIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void crossOrgRequest_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "tenant-other")
                .with(jwt().jwt(j -> j.claim("org_id", "tenant-a"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void intentNotFoundInOrg_returns404() throws Exception {
        when(deliveryLogService.listForIntent(eq("missing-intent"), eq("default"), anyInt(), anyInt()))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notify/intents/missing-intent/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void sizeAboveMaxRejected_with400_noSilentClamp() throws Exception {
        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "default")
                .param("size", "101")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation"));
    }

    @Test
    void validRequest_returnsRedactedListResponse() throws Exception {
        when(deliveryLogService.listForIntent(any(), any(), anyInt(), anyInt()))
            .thenReturn(Optional.of(new DeliveryLogListResponse(
                List.of(), 0, 20, 0L, 0, null, null, "v1"
            )));

        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redaction_policy").value("v1"))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.total_elements").value(0));
    }

    @Test
    void operatorRoleAlsoAccepted_forIntentEndpoint() throws Exception {
        when(deliveryLogService.listForIntent(any(), any(), anyInt(), anyInt()))
            .thenReturn(Optional.of(new DeliveryLogListResponse(
                List.of(), 0, 20, 0L, 0, null, null, "v1"
            )));

        mockMvc.perform(get("/api/v1/notify/intents/intent-1/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isOk());
    }

    @TestConfiguration
    static class NotifyConfigStub {
        @Bean
        @Primary
        public NotifyConfig notifyConfig() {
            return new NotifyConfig(
                new NotifyConfig.DispatchConfig(false),
                new NotifyConfig.IntakeConfig(10000),
                new NotifyConfig.IdempotencyConfig(24),
                new NotifyConfig.DedupeConfig(5),
                new NotifyConfig.RetryConfig(5, 30000L, 2.5d, 3600000L, 0.25d),
                new NotifyConfig.AuditConfig(90, false, "0 0 2 * * *", 24, false, 3, true),
                new NotifyConfig.RedactionConfig("test-pepper"),
                new NotifyConfig.WorkerConfig(5000L, 25, 50, 60000L, ""),
                new NotifyConfig.SecurityConfig(
                    "default", java.util.List.of("subscriberId", "userId", "sub"))
            );
        }
    }

    @TestConfiguration
    static class JwtDecoderStub {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised; jwt() postprocessor sets the principal directly");
            };
        }
    }
}
