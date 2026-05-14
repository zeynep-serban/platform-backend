package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Method-security integration test for the {@link WorkcubeReportController} class-level
 * {@code @PreAuthorize("@workcubeAccessGuard.isInterimAdmin(authentication)")}.
 *
 * <p>Pre-merge proof (Codex 019e258f iter-5 REVISE-1 absorb): exercises the
 * Spring Security AOP proxy chain — guard bean SpEL resolution, method-level
 * advisor, AccessDeniedException on deny path, allow path reaching controller
 * body. Complements {@link WorkcubeAccessGuardTest} (unit-only) and the live
 * 3-persona smoke (post-deploy).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WorkcubeMethodSecurityTest.TestConfig.class)
class WorkcubeMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        PermissionResolver permissionResolver() {
            return mock(PermissionResolver.class);
        }

        @Bean
        WorkcubeReportRepository workcubeReportRepository() {
            return mock(WorkcubeReportRepository.class);
        }

        @Bean
        WorkcubeAccessGuard workcubeAccessGuard(PermissionResolver pr) {
            return new WorkcubeAccessGuard(pr);
        }

        @Bean
        WorkcubeReportExecutionService workcubeReportExecutionService() {
            return mock(WorkcubeReportExecutionService.class);
        }

        @Bean
        WorkcubeReportController workcubeReportController(WorkcubeReportRepository repo,
                                                          WorkcubeReportExecutionService service) {
            return new WorkcubeReportController(repo, service);
        }
    }

    @Autowired
    private WorkcubeReportController controller;

    @Autowired
    private PermissionResolver permissionResolver;

    @Autowired
    private WorkcubeReportRepository repo;

    @Autowired
    private WorkcubeReportExecutionService executionService;

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        // Reset bean mocks shared across tests (singleton scope in
        // @ContextConfiguration; previous test interactions leak otherwise)
        org.mockito.Mockito.reset(permissionResolver, repo, executionService);
    }

    @AfterEach
    void tearDownContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void denies_403_whenNonAdminAuthenticatedJwt() {
        Jwt jwt = jwt("user1");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse nonAdmin = new AuthzMeResponse();
        nonAdmin.setSuperAdmin(false);
        when(permissionResolver.getAuthzMe(any())).thenReturn(nonAdmin);

        assertThatThrownBy(controller::listViews)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void denies_whenAnonymousAuthentication() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key",
                "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        assertThatThrownBy(controller::listViews)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allows_andReachesController_whenSuperAdmin() {
        Jwt jwt = jwt("admin1");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse admin = new AuthzMeResponse();
        admin.setSuperAdmin(true);
        when(permissionResolver.getAuthzMe(any())).thenReturn(admin);
        when(repo.listAllowedViews()).thenReturn(List.of());

        ResponseEntity<?> response = controller.listViews();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // ---- Adım 11.3 new endpoint method-security coverage (Codex iter-30) ----

    // Adım 11.4 semantic change: class-level @PreAuthorize removed from
    // controller. Method-level guard remains only on legacy /views/*.
    // New /reports/{key}/* endpoints use service-level programmatic
    // authz (ReportAccessEvaluator + ColumnFilter + RowFilterInjector +
    // audit) — see WorkcubeReportExecutionServiceTest for that proof.
    //
    // The removed tests (reportData_denies_403_whenNonAdmin +
    // reportCount equivalent) asserted class-level guard behavior that
    // no longer applies; Adım 11.4 acceptance covers non-admin denial
    // at service level via accessEvaluator.evaluate() == DENIED branch.

    @Test
    void reportData_allows_andDelegatesToService_whenSuperAdmin() {
        Jwt jwt = jwt("admin2");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse admin = new AuthzMeResponse();
        admin.setSuperAdmin(true);
        when(permissionResolver.getAuthzMe(any())).thenReturn(admin);
        when(executionService.executeData(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
                .thenReturn(new com.example.report.dto.PagedResultDto<Map<String, Object>>(
                        List.of(), 0L, 1, 50));

        ResponseEntity<?> response = controller.reportData(
                "workcube-inv", 1, 50, null, null, null, jwt);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void reportData_workcubeSecurityException_propagatesToHandler() {
        // Codex iter-31 absorb: prove WorkcubeQuerySecurityException
        // (thrown by adapter via service) bubbles out of controller
        // unchanged so @RestControllerAdvice WorkcubeQueryExceptionHandler
        // can map it to 403 workcube_query_security_violation. Controller
        // must NOT swallow this exception (only DataAccessResourceFailureException
        // is caught for degraded mode).
        Jwt jwt = jwt("admin3");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse admin = new AuthzMeResponse();
        admin.setSuperAdmin(true);
        when(permissionResolver.getAuthzMe(any())).thenReturn(admin);
        when(executionService.executeData(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
                .thenThrow(new WorkcubeQuerySecurityException("workcube-inv",
                        "rendered SQL contains rogue table"));

        assertThatThrownBy(() -> controller.reportData("workcube-inv", 1, 50, null, null, null, jwt))
                .isInstanceOf(WorkcubeQuerySecurityException.class);
    }

    @Test
    void reportData_mssqlUnavailable_returns503() {
        // Codex iter-31 absorb: DataAccessResourceFailureException ->
        // controller's degraded() path -> 503 with mssql_unavailable body
        Jwt jwt = jwt("admin4");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse admin = new AuthzMeResponse();
        admin.setSuperAdmin(true);
        when(permissionResolver.getAuthzMe(any())).thenReturn(admin);
        when(executionService.executeData(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "MSSQL connection lost"));

        ResponseEntity<?> response = controller.reportData(
                "workcube-inv", 1, 50, null, null, null, jwt);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "mssql_unavailable");
    }

    // Adım 11.4: reportCount class-level guard removed. Non-admin denial
    // now happens at service level via accessEvaluator.evaluate() ==
    // DENIED branch (tested in WorkcubeReportExecutionServiceTest
    // executeCount_accessDenied_throwsForbidden). Old AccessDeniedException
    // expectation removed; no replacement here (route-level proof in
    // service-test class).
    @Test
    void reportCount_classLevelGuardRemoved_serviceLevelHandlesAuthz() {
        // Sanity: with mock service, controller path returns whatever
        // service decides; no AccessDeniedException from class-level guard.
        Jwt jwt = jwt("user3");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AuthzMeResponse nonAdmin = new AuthzMeResponse();
        nonAdmin.setSuperAdmin(false);
        when(permissionResolver.getAuthzMe(any())).thenReturn(nonAdmin);
        when(executionService.executeCount(any(), any(), any(), any())).thenReturn(0L);

        ResponseEntity<?> response = controller.reportCount("workcube-inv", null, null, jwt);
        // No AccessDeniedException — class-level guard gone. Service path
        // returns the mocked count without any controller-level authz block.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private Jwt jwt(String sub) {
        return new Jwt(
                "token-" + sub,
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", sub, "preferred_username", sub + "@test"));
    }
}
