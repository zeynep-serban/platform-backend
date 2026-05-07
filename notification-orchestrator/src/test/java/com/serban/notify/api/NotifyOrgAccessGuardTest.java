package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NotifyOrgAccessGuard} (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE: org-boundary tests must use a
 * real {@code JwtAuthenticationToken} so the claim resolution path is
 * exercised — {@code @WithMockUser} is insufficient because it sets a
 * principal that does not satisfy the {@code instanceof JwtAuthenticationToken}
 * branch.
 */
class NotifyOrgAccessGuardTest {

    private static final NotifyConfig.SecurityConfig DEFAULT_SECURITY =
        new NotifyConfig.SecurityConfig("default");

    private static final NotifyConfig.SecurityConfig EMPTY_SECURITY =
        new NotifyConfig.SecurityConfig("");

    private final NotifyOrgAccessGuard guardWithDefault = guard(DEFAULT_SECURITY);
    private final NotifyOrgAccessGuard guardWithoutDefault = guard(EMPTY_SECURITY);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blankRequestedOrg_throwsIllegalArgument() {
        assertThatThrownBy(() -> guardWithDefault.requireOrgAccessOrThrow(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guardWithDefault.requireOrgAccessOrThrow(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAuthentication_silentPass() {
        // No SecurityContext → slice tests pass without principal.
        guardWithDefault.requireOrgAccessOrThrow("any-org");
    }

    @Test
    void anonymousAuth_silentPass() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken("k", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANON")))
        );
        // anonymous is technically authenticated() == true on AnonymousAuthenticationToken;
        // the matchesClaim path returns false and we fall through to default-org
        // which is "default" — but requested is "other" so this should DENY.
        assertThatThrownBy(() ->
            guardWithDefault.requireOrgAccessOrThrow("other"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void claim_org_id_match_passes() {
        setJwt(Map.of("org_id", "tenant-a"));
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-a");
    }

    @Test
    void claim_tenant_id_match_passes_whenOrgIdMissing() {
        setJwt(Map.of("tenant_id", "tenant-b"));
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-b");
    }

    @Test
    void claim_allowed_orgs_list_match_passes() {
        setJwt(Map.of("allowed_orgs", List.of("tenant-x", "tenant-y")));
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-y");
    }

    @Test
    void claim_priority_org_id_winsOverTenantAndAllowedOrgs() {
        setJwt(Map.of(
            "org_id", "tenant-priority",
            "tenant_id", "tenant-secondary",
            "allowed_orgs", List.of("tenant-tertiary")
        ));
        // Match found by the org_id claim (highest priority).
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-priority");
        // Lower priority claims still match if requested matches them.
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-secondary");
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-tertiary");
    }

    @Test
    void mismatch_throwsAccessDenied_whenNoFallback() {
        setJwt(Map.of("org_id", "tenant-a"));
        assertThatThrownBy(() ->
            guardWithoutDefault.requireOrgAccessOrThrow("tenant-other"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void defaultOrgFallback_appliesOnly_whenRequestedEqualsDefault() {
        setJwt(Map.of()); // no org claims
        // request == default → pass
        guardWithDefault.requireOrgAccessOrThrow("default");
        // request != default → deny (NOT a wildcard)
        assertThatThrownBy(() ->
            guardWithDefault.requireOrgAccessOrThrow("tenant-other"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void crossOrgPermission_bypassesOrgCheck() {
        setJwt(Map.of("org_id", "tenant-a"), "notify-deliveries-cross-org");
        // Even though JWT only allows tenant-a, the cross-org permission
        // unlocks any org id (forward-compat — v1 catalogue does NOT seed
        // this permission, but the guard accepts it if present).
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-z");
    }

    @Test
    void blankDefaultOrgConfig_disablesFallback() {
        setJwt(Map.of()); // no org claims
        // Empty default-org-id → no fallback at all.
        assertThatThrownBy(() ->
            guardWithoutDefault.requireOrgAccessOrThrow("default"))
            .isInstanceOf(AccessDeniedException.class);
    }

    private NotifyOrgAccessGuard guard(NotifyConfig.SecurityConfig security) {
        NotifyConfig config = new NotifyConfig(
            new NotifyConfig.DispatchConfig(false),
            new NotifyConfig.IntakeConfig(10000),
            new NotifyConfig.IdempotencyConfig(24),
            new NotifyConfig.DedupeConfig(5),
            new NotifyConfig.RetryConfig(5, 30000L, 2.5d, 3600000L, 0.25d),
            new NotifyConfig.AuditConfig(90, false, "0 0 2 * * *", 24, false, 3, true),
            new NotifyConfig.RedactionConfig("test-pepper"),
            new NotifyConfig.WorkerConfig(5000L, 25, 50, 60000L, ""),
            security
        );
        return new NotifyOrgAccessGuard(config);
    }

    private void setJwt(Map<String, Object> claims, String... extraAuthorities) {
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
            .header("alg", "RS256")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .subject("subject-id");
        claims.forEach(builder::claim);
        Jwt jwt = builder.build();

        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("audit-read"));
        for (String extra : extraAuthorities) {
            authorities.add(new SimpleGrantedAuthority(extra));
        }
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
