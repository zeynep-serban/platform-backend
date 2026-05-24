package com.serban.notify.authz;

import com.serban.notify.config.NotifyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DpoUserIdResolver unit tests (Faz 23.2.B PR-K6 — Codex {@code 019e59ea}
 * iter-2 mandatory fix: jwt.sub is the Keycloak UUID and produces wrong
 * OpenFGA tuple namespace).
 *
 * <p>Verifies the bounded claim allowlist contract:
 * <ul>
 *   <li>No Authentication ⇒ null (slice-test silent-pass)</li>
 *   <li>Non-Jwt Authentication ⇒ null</li>
 *   <li>JWT with no configured claims present ⇒ null (never falls back
 *       to {@code sub})</li>
 *   <li>JWT with primary claim ({@code userId}) ⇒ returns userId value</li>
 *   <li>JWT with legacy fallback claim ({@code uid}) but no primary ⇒
 *       returns uid value</li>
 *   <li>JWT with both ⇒ primary claim ({@code userId}) wins (order
 *       preserved)</li>
 *   <li>Blank claim value ⇒ skipped, falls through to next claim</li>
 *   <li>Numeric claim value ⇒ stringified</li>
 * </ul>
 */
class DpoUserIdResolverTest {

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private DpoUserIdResolver resolver() {
        return resolver(List.of("userId", "uid"));
    }

    private DpoUserIdResolver resolver(List<String> claims) {
        NotifyConfig config = new NotifyConfig(
            new NotifyConfig.DispatchConfig(false),
            new NotifyConfig.IntakeConfig(10000),
            new NotifyConfig.IdempotencyConfig(24),
            new NotifyConfig.DedupeConfig(5),
            new NotifyConfig.RetryConfig(5, 30000L, 2.5d, 3600000L, 0.25d),
            new NotifyConfig.AuditConfig(90, false, "0 0 2 * * *", 24, false, 3, true),
            new NotifyConfig.RedactionConfig("test-pepper"),
            new NotifyConfig.WorkerConfig(5000L, 25, 50, 60000L, ""),
            new NotifyConfig.SecurityConfig("default",
                List.of("subscriberId", "userId", "sub"), false),
            new NotifyConfig.KvkkConfig(true, claims)
        );
        return new DpoUserIdResolver(config);
    }

    @Test
    void noAuthentication_returnsNull() {
        SecurityContextHolder.clearContext();

        assertThat(resolver().resolveOrNull()).isNull();
    }

    @Test
    void nonJwtAuthentication_returnsNull() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                AuthorityUtils.createAuthorityList("ROLE_USER"))
        );

        assertThat(resolver().resolveOrNull()).isNull();
    }

    @Test
    void anonymousAuthentication_returnsNull() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        );

        assertThat(resolver().resolveOrNull()).isNull();
    }

    @Test
    void jwtWithUserIdClaim_returnsUserId() {
        setJwtClaims(Map.of("userId", "1204"));

        assertThat(resolver().resolveOrNull()).isEqualTo("1204");
    }

    @Test
    void jwtWithUidFallback_returnsUid() {
        setJwtClaims(Map.of("uid", "1295"));

        assertThat(resolver().resolveOrNull()).isEqualTo("1295");
    }

    @Test
    void jwtWithBothClaims_userIdWins() {
        setJwtClaims(Map.of("userId", "1204", "uid", "9999"));

        // Configured order [userId, uid] — first non-blank wins.
        assertThat(resolver().resolveOrNull()).isEqualTo("1204");
    }

    @Test
    void jwtWithSubButNoUserIdOrUid_returnsNull() {
        // Critical contract: sub fallback is NOT permitted because sub
        // is the Keycloak UUID (e.g. "5f1c-...") while OpenFGA tuples
        // are written with the numeric DB user id.
        setJwtClaims(Map.of("sub", "5f1c2a8e-aaaa-bbbb-cccc-deadbeefcafe"));

        assertThat(resolver().resolveOrNull()).isNull();
    }

    @Test
    void jwtWithBlankUserIdAndUid_returnsNull() {
        setJwtClaims(Map.of("userId", "   ", "uid", ""));

        assertThat(resolver().resolveOrNull()).isNull();
    }

    @Test
    void jwtWithBlankUserIdButValidUid_returnsUid() {
        setJwtClaims(Map.of("userId", "  ", "uid", "1295"));

        assertThat(resolver().resolveOrNull()).isEqualTo("1295");
    }

    @Test
    void jwtWithNumericClaim_returnsStringified() {
        // JWT claim could be parsed as Long by Spring; resolver must
        // tolerate non-String values.
        setJwtClaims(Map.of("userId", 1204));

        assertThat(resolver().resolveOrNull()).isEqualTo("1204");
    }

    @Test
    void customClaimList_singleClaim() {
        setJwtClaims(Map.of("userId", "1204", "uid", "9999"));
        DpoUserIdResolver r = resolver(List.of("uid"));

        // Custom list [uid] only — userId is ignored even when present.
        assertThat(r.resolveOrNull()).isEqualTo("9999");
    }

    @Test
    void noConfiguredClaimMatches_returnsNull() {
        setJwtClaims(Map.of("preferred_username", "halil"));

        assertThat(resolver().resolveOrNull()).isNull();
    }

    /**
     * Sets a minimal {@link JwtAuthenticationToken} on the SecurityContext
     * with the given claims; signature/header content is irrelevant for
     * claim-extraction tests.
     */
    private void setJwtClaims(Map<String, Object> claims) {
        Jwt jwt = new Jwt(
            "test-token",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            Map.of("alg", "none"),
            claims
        );
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_PRIVACY_OFFICER"))
        );
    }
}
