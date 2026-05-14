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
        WorkcubeReportController workcubeReportController(WorkcubeReportRepository repo) {
            return new WorkcubeReportController(repo);
        }
    }

    @Autowired
    private WorkcubeReportController controller;

    @Autowired
    private PermissionResolver permissionResolver;

    @Autowired
    private WorkcubeReportRepository repo;

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
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

    private Jwt jwt(String sub) {
        return new Jwt(
                "token-" + sub,
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", sub, "preferred_username", sub + "@test"));
    }
}
