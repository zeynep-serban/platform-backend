package com.serban.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.authz.DpoAuthzService;
import com.serban.notify.authz.DpoUserIdResolver;
import com.serban.notify.config.SecurityConfig;
import com.serban.notify.erasure.ErasureService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminErasureController K6 tenant-scoped DPO authz controller-slice test
 * (Faz 23.2.B PR-K6 — Codex thread {@code 019e59ea} iter-3 AGREE absorb).
 *
 * <p>Covers the K6 decision matrix demanded by Codex iter-2 review:
 *
 * <ul>
 *   <li>(a) DPO of org-X with DPO tuple → POST orgId=X → 200</li>
 *   <li>(b) DPO of org-X holding multi-org JWT [X,Y] → POST orgId=Y →
 *       403 from K6 DPO check (NOT from orgAccessGuard — this is the
 *       critical iter-1 correction; if Y were not in trusted set,
 *       orgGuard would 403 first and hide the K6 path)</li>
 *   <li>(c) Non-DPO user → POST orgId=X → 403 from K6 DPO check</li>
 *   <li>(e) flag=false bypass: no DPO tuple but ROLE_PRIVACY_OFFICER →
 *       200 (legacy stack unaffected)</li>
 *   <li>(f) flag=true + permission-service unreachable → 403 fail-closed
 *       (DpoAuthzService inherits AuthzClient.deny(authz_unreachable))</li>
 *   <li>(g) Admin derivation: organization#admin@user → 200 via
 *       can_erasure userset rewrite (defense-in-depth policy)</li>
 * </ul>
 *
 * <p>Strategy: this slice mocks {@link DpoAuthzService} directly so we
 * test the controller-side guard composition rather than the
 * AuthzClient HTTP wire. The HTTP-level path is covered by the
 * permission-service {@code InternalAuthorizationControllerTest} K6
 * accept-path test ({@code userPrincipalAccepted_K6_dpoAuthz}); the
 * end-to-end model+tuple round-trip is acceptance-tested in the
 * follow-up Testcontainers IT after OpenFGA model promotion.
 */
@WebMvcTest(controllers = AdminErasureController.class,
    properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/realms/serban"
    })
@Import({SecurityConfig.class, AdminErasureControllerK6DpoAuthzTest.SecurityTestConfig.class})
@ActiveProfiles("security-test")
class AdminErasureControllerK6DpoAuthzTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ErasureService erasureService;
    @MockBean NotifyOrgAccessGuard orgAccessGuard;
    @MockBean DpoAuthzService dpoAuthzService;
    @MockBean DpoUserIdResolver dpoUserIdResolver;

    @BeforeEach
    void seedSuccessfulErasure() {
        when(erasureService.eraseSubscriber(any())).thenReturn(
            new ErasureService.EraseResult(1, 2, 0)
        );
        // Default: resolver returns the canonical numeric user id.
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
     * Case (a): DPO of org-X with seeded
     * `organization:org-X#dpo@user:1204` tuple ⇒ 200.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void dpoForOwnOrg_returns200() throws Exception {
        when(dpoAuthzService.canEraseForOrg("1204", "org-X")).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isOk());

        verify(erasureService).eraseSubscriber(any());
    }

    /**
     * Case (b) — Codex iter-1 critical fix verification: DPO of org-X
     * with `allowed_orgs:[X,Y]` invoking erasure for org-Y must hit the
     * K6 DPO check (not the orgAccessGuard). orgAccessGuard.requireOrgAccessOrThrow
     * is stubbed to permit (no throw — JWT trusted set DOES include Y),
     * but DpoAuthzService denies because no
     * `organization:org-Y#can_erasure@user:1204` tuple is seeded.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void dpoForOrgX_butRequestForOrgY_K6Denies() throws Exception {
        // orgAccessGuard passes — JWT trusted set covers both orgs.
        // Mockito void method default = no-op; explicit no throw makes
        // intent crystal clear.
        org.mockito.Mockito.doNothing().when(orgAccessGuard)
            .requireOrgAccessOrThrow(eq("org-Y"));
        // K6 denies — user is DPO of X only.
        when(dpoAuthzService.canEraseForOrg("1204", "org-Y")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-Y")))
            .andExpect(status().isForbidden());

        // K6 throws BEFORE ErasureService is called.
        verify(erasureService, never()).eraseSubscriber(any());
    }

    /**
     * Case (c): non-DPO user (ROLE_PRIVACY_OFFICER role only, no DPO
     * tuple) ⇒ K6 denies ⇒ 403.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void nonDpoUser_returns403() throws Exception {
        when(dpoUserIdResolver.resolveOrNull()).thenReturn("9999");
        when(dpoAuthzService.canEraseForOrg("9999", "org-X")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isForbidden());

        verify(erasureService, never()).eraseSubscriber(any());
    }

    /**
     * Case (e) flag=false bypass: legacy stack only. DpoAuthzService
     * canEraseForOrg returns true unconditionally (short-circuit on
     * !enabled). No DPO tuple required.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void flagOffLegacyBypass_returns200() throws Exception {
        // DpoUserIdResolver may return null (no userId claim) — the
        // service short-circuits on flag=off before checking userId.
        when(dpoUserIdResolver.resolveOrNull()).thenReturn(null);
        // Flag-off short-circuit = always true.
        when(dpoAuthzService.canEraseForOrg(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isOk());

        verify(erasureService).eraseSubscriber(any());
    }

    /**
     * Case (f) flag=true + permission-service unreachable: DpoAuthzService
     * returns false (inherited from AuthzClient.deny("authz_unreachable"))
     * ⇒ 403 fail-closed. Critical security invariant — never
     * default-allow on auth subsystem failure.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void flagOnPermissionServiceDown_failClosed403() throws Exception {
        when(dpoAuthzService.canEraseForOrg("1204", "org-X")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isForbidden());

        verify(erasureService, never()).eraseSubscriber(any());
    }

    /**
     * Case (g) Admin derivation: organization:org-X#admin@user:9001 +
     * ROLE_PRIVACY_OFFICER + flag=true → 200 via the model rewrite
     * `can_erasure: [user] or dpo or admin`. This is the bilinçli policy
     * choice Codex iter-2 explicitly tested: org admins retain
     * operational erasure pathway when no dedicated DPO is designated.
     * Removing `or admin` from the model is the alternative for strict
     * DPO-only operations; document tradeoff in runbook.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void adminDerivation_orgAdminCanErase() throws Exception {
        when(dpoUserIdResolver.resolveOrNull()).thenReturn("9001");
        // Admin tuple exists; userset rewrite resolves can_erasure → true.
        when(dpoAuthzService.canEraseForOrg("9001", "org-X")).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isOk());

        verify(erasureService).eraseSubscriber(any());
    }

    /**
     * Case (h) extra defense-in-depth verification: when flag=true but
     * DpoUserIdResolver cannot find any of the configured claims
     * (resolver returned null), DpoAuthzService denies. Without this
     * guard, K6 enable in production could be silently bypassed for
     * tokens that lack the userId claim.
     */
    @Test
    @WithMockUser(authorities = {"ROLE_PRIVACY_OFFICER"})
    void flagOnButResolverReturnsNull_failClosed403() throws Exception {
        when(dpoUserIdResolver.resolveOrNull()).thenReturn(null);
        // DpoAuthzService.canEraseForOrg(null, orgId) returns false when
        // enabled (defensive null guard before HTTP call).
        when(dpoAuthzService.canEraseForOrg(null, "org-X")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/notify/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content(erasureBody("org-X")))
            .andExpect(status().isForbidden());

        verify(erasureService, never()).eraseSubscriber(any());
    }

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
