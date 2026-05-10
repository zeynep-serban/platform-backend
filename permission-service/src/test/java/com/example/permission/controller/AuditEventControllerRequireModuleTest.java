package com.example.permission.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationAuditEventTypes;
import com.example.permission.config.RequireModuleInterceptor;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.service.AuditEventService;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthenticatedUserLookupService.ResolvedAuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-D2: cross-AI peer review (Codex iter-2) reminder — the dedicated
 * {@code /api/audit/events/impersonation} endpoint must be tested with the
 * {@link RequireModuleInterceptor} ACTIVE, otherwise the
 * {@code @RequireModule("IMPERSONATION_AUDIT", ...)} change is unverified.
 * The other slice test ({@link AuditEventControllerTest}) uses
 * {@code addFilters=false} which skips Spring Security but in standalone
 * MockMvc setup we register the interceptor ourselves.
 *
 * <p>Coverage:
 * <ul>
 *   <li>IMPERSONATION_AUDIT.can_view granted → 200 OK</li>
 *   <li>AUDIT.can_view granted but IMPERSONATION_AUDIT denied → 403 Forbidden</li>
 *   <li>Org admin bypass → 200 OK regardless of module-level grants</li>
 * </ul>
 */
class AuditEventControllerRequireModuleTest {

    private OpenFgaAuthzService authzService;
    private AuthenticatedUserLookupService userLookupService;
    private AuditEventService auditEventService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authzService = mock(OpenFgaAuthzService.class);
        userLookupService = mock(AuthenticatedUserLookupService.class);
        auditEventService = mock(AuditEventService.class);

        when(authzService.isEnabled()).thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuditEventController(auditEventService))
                .addInterceptors(new RequireModuleInterceptor(authzService, userLookupService))
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("IMPERSONATION_AUDIT.can_view granted → 200 OK on /impersonation")
    void impersonationAuditViewerCanRead() throws Exception {
        Jwt jwt = jwt(1100L, "imp-auditor@example.com");
        authenticate(jwt);
        when(userLookupService.resolve(jwt))
                .thenReturn(new ResolvedAuthenticatedUser(1100L, "1100", "imp-auditor@example.com"));
        // Not org admin
        when(authzService.check(eq("1100"), eq("admin"), eq("organization"), eq("default"))).thenReturn(false);
        // IMPERSONATION_AUDIT.can_view granted
        when(authzService.check(eq("1100"), eq("can_view"), eq("module"), eq("IMPERSONATION_AUDIT"))).thenReturn(true);

        AuditEventResponse e = new AuditEventResponse(
                "1", Instant.now(), "admin@example.com", "permission-service",
                "INFO", ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
                "x", "c-1", Map.of(), Map.of(), Map.of());
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT)))
                .thenReturn(new AuditEventPageResponse(List.of(e), 0, 1));

        mockMvc.perform(get("/api/audit/events/impersonation"))
                .andExpect(status().isOk());

        verify(authzService).check(eq("1100"), eq("can_view"), eq("module"), eq("IMPERSONATION_AUDIT"));
    }

    @Test
    @DisplayName("AUDIT viewer without IMPERSONATION_AUDIT.can_view → 403 on /impersonation")
    void auditViewerCannotReadImpersonationFeed() throws Exception {
        Jwt jwt = jwt(1101L, "audit-only@example.com");
        authenticate(jwt);
        when(userLookupService.resolve(jwt))
                .thenReturn(new ResolvedAuthenticatedUser(1101L, "1101", "audit-only@example.com"));
        when(authzService.check(eq("1101"), eq("admin"), eq("organization"), eq("default"))).thenReturn(false);
        // AUDIT.can_view granted (irrelevant for this path) but
        // IMPERSONATION_AUDIT.can_view DENIED — the interceptor reads the
        // module from @RequireModule, so AUDIT grants do not bleed across.
        when(authzService.check(eq("1101"), eq("can_view"), eq("module"), eq("AUDIT"))).thenReturn(true);
        when(authzService.check(eq("1101"), eq("can_view"), eq("module"), eq("IMPERSONATION_AUDIT"))).thenReturn(false);

        mockMvc.perform(get("/api/audit/events/impersonation"))
                .andExpect(status().isForbidden());

        // Service must NOT be invoked
        verify(auditEventService, never()).listEvents(anyInt(), anyInt(), any(), any(), any(AuditReadScope.class));
    }

    @Test
    @DisplayName("Org admin → bypass module check on /impersonation")
    void orgAdminBypassesImpersonationGuard() throws Exception {
        Jwt jwt = jwt(1102L, "super@example.com");
        authenticate(jwt);
        when(userLookupService.resolve(jwt))
                .thenReturn(new ResolvedAuthenticatedUser(1102L, "1102", "super@example.com"));
        // Org admin bypass kicks in
        when(authzService.check(eq("1102"), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        AuditEventPageResponse page = new AuditEventPageResponse(List.of(), 0, 0);
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT)))
                .thenReturn(page);

        mockMvc.perform(get("/api/audit/events/impersonation"))
                .andExpect(status().isOk());

        // Module-level check should NOT be invoked (bypass)
        verify(authzService, never()).check(eq("1102"), eq("can_view"), eq("module"), eq("IMPERSONATION_AUDIT"));
    }

    private static Jwt jwt(long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("sub", "subject-" + userId);
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static void authenticate(Jwt jwt) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null, "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
