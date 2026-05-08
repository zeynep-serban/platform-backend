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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security slice + auth boundary tests for {@link AdminDeliveryController}
 * (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE: org-boundary tests use
 * the {@code jwt()} postprocessor; admin endpoint requires
 * {@code audit-read} or {@code ROLE_ADMIN} (NOT {@code ROLE_OPERATOR}).
 */
@WebMvcTest(controllers = AdminDeliveryController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({
    SecurityConfig.class,
    NotifyOrgAccessGuard.class,
    AdminDeliveryControllerWebMvcTest.NotifyConfigStub.class,
    AdminDeliveryControllerWebMvcTest.JwtDecoderStub.class,
    AdminDeliveryControllerWebMvcTest.MeterRegistryStub.class
})
@ActiveProfiles("security-test")
class AdminDeliveryControllerWebMvcTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DeliveryLogService deliveryLogService;

    @Test
    void anonymousRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .with(anonymous()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorRoleRejected_forAdminEndpoint() throws Exception {
        // Critical regression: ROLE_OPERATOR is allowed on the intent
        // endpoint but MUST NOT unlock fleet-wide admin search.
        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void auditReadAcceptedForAdminEndpoint() throws Exception {
        when(deliveryLogService.searchAdmin(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(emptyResponse(OffsetDateTime.parse("2026-05-07T08:00:00Z")));

        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isOk());
    }

    @Test
    void roleAdminAcceptedForAdminEndpoint() throws Exception {
        when(deliveryLogService.searchAdmin(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(emptyResponse(OffsetDateTime.parse("2026-05-07T08:00:00Z")));

        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }

    @Test
    void crossOrgSearchRejectedInV1() throws Exception {
        // JWT trusts tenant-a, but X-Org-Id requests tenant-b → 403.
        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "tenant-b")
                .with(jwt().jwt(j -> j.claim("org_id", "tenant-a"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void sizeAboveMaxRejected_with400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .param("size", "200")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation"));
    }

    @Test
    void rangeAboveSevenDaysRejected_with400() throws Exception {
        OffsetDateTime tooFarBack = OffsetDateTime.now().minusDays(8);
        OffsetDateTime now = OffsetDateTime.now();

        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .param("from", tooFarBack.toString())
                .param("to", now.toString())
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void missingXOrgIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void validRequest_returnsRedactedShape() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T08:00:00Z");
        when(deliveryLogService.searchAdmin(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(emptyResponse(now));

        mockMvc.perform(get("/api/v1/admin/notify/deliveries")
                .header("X-Org-Id", "default")
                .param("status", "FAILED")
                .with(jwt().jwt(j -> j.claim("org_id", "default"))
                    .authorities(new SimpleGrantedAuthority("audit-read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redaction_policy").value("v1"))
            .andExpect(jsonPath("$.from").exists())
            .andExpect(jsonPath("$.to").exists());
    }

    private DeliveryLogListResponse emptyResponse(OffsetDateTime now) {
        return new DeliveryLogListResponse(
            List.of(), 0, 20, 0L, 0, now.minusHours(24), now, "v1"
        );
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
                new NotifyConfig.SecurityConfig("default", java.util.List.of("subscriberId", "userId", "sub"), false)
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

    /**
     * Faz 24 / PR-5.1 (Codex thread `019e040c`): NotifyOrgAccessGuard
     * now needs a MeterRegistry. WebMvcTest slices don't auto-load
     * the actuator stack, so provide a SimpleMeterRegistry bean
     * explicitly.
     */
    @TestConfiguration
    static class MeterRegistryStub {
        @Bean
        @Primary
        public io.micrometer.core.instrument.MeterRegistry testMeterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }
}
