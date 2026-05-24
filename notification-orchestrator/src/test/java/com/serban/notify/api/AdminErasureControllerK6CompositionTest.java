package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.authz.DpoAuthzService;
import com.serban.notify.authz.DpoUserIdResolver;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.erasure.ErasureService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminErasureController K6 composition test (Codex post-impl review
 * {@code 019e59ea} REVISE absorb).
 *
 * <p>This test answers Codex's blocking finding on
 * {@link AdminErasureControllerK6DpoAuthzTest}: that test mocks {@link
 * NotifyOrgAccessGuard} directly, so case (b) "DPO of org-X holding
 * multi-org JWT [X, Y] invoking erasure for org-Y" did not actually
 * exercise the real {@code allowed_orgs} claim path through
 * {@code NotifyOrgAccessGuard.requireOrgAccessOrThrow}. The K6 deny
 * could in principle have been hidden by an earlier orgGuard 403.
 *
 * <p>This slice uses the real {@link NotifyOrgAccessGuard} bean (Spring
 * picks it up via component scan / {@link Import}) and a real
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}
 * built via {@code SecurityMockMvcRequestPostProcessors.jwt()} carrying
 * {@code allowed_orgs:[org-X, org-Y]} + {@code userId} claim +
 * {@code ROLE_PRIVACY_OFFICER} authority.
 *
 * <p>Only {@link DpoAuthzService} is mocked — letting the real
 * orgGuard permit BOTH org-X and org-Y from the JWT trusted set, then
 * the K6 gate decides per-org. This is the agreed acceptance shape from
 * the Codex iter-1 critical fix.
 */
@WebMvcTest(controllers = AdminErasureController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban",
        // Pin default-org so the NotifyOrgAccessGuard fallback doesn't
        // mask the allowed_orgs path under test.
        "notify.security.default-org-id=default"
    })
@Import({
    SecurityConfig.class,
    AdminErasureControllerK6CompositionTest.RealOrgGuardConfig.class,
    AdminErasureControllerK6CompositionTest.SecurityTestConfig.class
})
@ActiveProfiles("security-test")
class AdminErasureControllerK6CompositionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ErasureService erasureService;
    @MockBean DpoAuthzService dpoAuthzService;
    @MockBean DpoUserIdResolver dpoUserIdResolver;
    // NotifyOrgAccessGuard is NOT @MockBean — RealOrgGuardConfig provides
    // a real instance backed by SimpleMeterRegistry.

    @BeforeEach
    void seedHappyPathErasure() {
        when(erasureService.eraseSubscriber(any())).thenReturn(
            new ErasureService.EraseResult(1, 2, 0)
        );
        // Default: the resolver returns the canonical numeric user id
        // — matches the JWT userId claim we set on every request below.
        when(dpoUserIdResolver.resolveOrNull()).thenReturn("1204");
    }

    private String erasureBody(String orgId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "org_id", orgId,
            "subscriber_id", "sub-1",
            "reason", "kvkk-art-11",
            "evidence_ref", "TICKET-DPO-K6"
        ));
    }

    /**
     * Composition case (b): JWT carries {@code allowed_orgs:[org-X,
     * org-Y]} → real {@link NotifyOrgAccessGuard} permits both orgs
     * via the trusted set. Request body is {@code org_id=org-Y} (the
     * DPO has tuple for X, not Y) → real {@code orgGuard} passes →
     * {@code DpoAuthzService.canEraseForOrg("1204", "org-Y")} returns
     * false → 403 from K6.
     *
     * <p>This proves the K6 enforcement path is reachable AFTER the
     * orgGuard PERMITS a multi-org JWT — the deny is genuinely the
     * K6 layer, not orgGuard. ErasureService is never called.
     */
    @Test
    void multiOrgJwt_orgGuardPermits_K6DeniesOrgY_returns403() throws Exception {
        when(dpoAuthzService.canEraseForOrg("1204", "org-Y")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .with(jwt()
                    .jwt(builder -> builder
                        .claim("userId", "1204")
                        .claim("allowed_orgs", List.of("org-X", "org-Y")))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_PRIVACY_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-Y")))
            .andExpect(status().isForbidden());

        verify(erasureService, never()).eraseSubscriber(any());
    }

    /**
     * Composition case (a) inverse: same JWT, request body
     * {@code org_id=org-X} → real orgGuard permits → K6 permits → 200.
     * Symmetric coverage for the X side of the multi-org JWT.
     */
    @Test
    void multiOrgJwt_orgGuardPermits_K6AllowsOrgX_returns200() throws Exception {
        when(dpoAuthzService.canEraseForOrg("1204", "org-X")).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .with(jwt()
                    .jwt(builder -> builder
                        .claim("userId", "1204")
                        .claim("allowed_orgs", List.of("org-X", "org-Y")))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_PRIVACY_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isOk());

        verify(erasureService).eraseSubscriber(any());
    }

    /**
     * Real orgGuard 403 path verification: JWT carries only
     * {@code allowed_orgs:[org-X]} → request for {@code org-Y} → real
     * orgGuard denies BEFORE K6 layer. This proves the orgGuard chain
     * works as expected for genuinely-untrusted orgs (the case (b)
     * above being a separate concern when both orgs are trusted).
     *
     * <p>K6 never invoked — {@code dpoAuthzService.canEraseForOrg}
     * verified never called.
     */
    @Test
    void singleOrgJwt_orgGuardDeniesOrgY_returns403_K6NotInvoked() throws Exception {
        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .with(jwt()
                    .jwt(builder -> builder
                        .claim("userId", "1204")
                        .claim("allowed_orgs", List.of("org-X")))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_PRIVACY_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-Y")))
            .andExpect(status().isForbidden());

        verify(dpoAuthzService, never()).canEraseForOrg(any(), any());
        verify(erasureService, never()).eraseSubscriber(any());
    }

    /**
     * Provides a REAL {@link NotifyOrgAccessGuard} bean (not @MockBean)
     * with all dependencies it needs from a minimal {@link NotifyConfig}.
     * The SimpleMeterRegistry is required by the guard constructor.
     */
    @TestConfiguration
    static class RealOrgGuardConfig {

        @Bean
        @Primary
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

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
                    "default",
                    List.of("subscriberId", "userId", "sub"),
                    false
                ),
                new NotifyConfig.KvkkConfig(false, List.of("userId", "uid"))
            );
        }

        @Bean
        @Primary
        public NotifyOrgAccessGuard notifyOrgAccessGuard(
            NotifyConfig notifyConfig, MeterRegistry meterRegistry
        ) {
            return new NotifyOrgAccessGuard(notifyConfig, meterRegistry);
        }
    }

    @TestConfiguration
    static class SecurityTestConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                    "JwtDecoder not exercised in jwt() postProcessor tests");
            };
        }
    }
}
