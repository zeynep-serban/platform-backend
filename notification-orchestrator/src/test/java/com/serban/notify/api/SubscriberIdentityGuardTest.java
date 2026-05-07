package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SubscriberIdentityGuard} (Faz 23.4 PR-E.5).
 *
 * <p>Test scope:
 * <ul>
 *   <li>JWT principal {@code sub} matches subscriberId → silent pass</li>
 *   <li>Mismatch → {@link AccessDeniedException}</li>
 *   <li>Null/empty inputs → defensive {@link AccessDeniedException}</li>
 *   <li>No SecurityContext authentication → silent pass (slice-test escape hatch)</li>
 *   <li>Non-JWT principal (e.g. UsernamePasswordAuthenticationToken) → silent pass</li>
 * </ul>
 */
class SubscriberIdentityGuardTest {

    private static final List<String> LEGACY_CLAIMS =
        List.of("subscriberId", "userId", "sub");

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SubscriberIdentityGuard guard = new SubscriberIdentityGuard(
        new NotifyConfig.SecurityConfig("default", LEGACY_CLAIMS), meterRegistry);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesWhenJwtSubjectMatchesSubscriberId() {
        // sub claim is in the trusted set (priority 3). Match wins.
        Jwt jwt = newJwt("alice");
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")), "alice")
        );

        assertThatCode(() -> guard.requireMatchOrThrow("alice")).doesNotThrowAnyException();
    }

    @Test
    void passesWhenJwtUserIdClaimMatchesSubscriberId() {
        // userId claim is the canonical "today" identifier — permission-service
        // JWT enrichment emits it (numeric DB user id). When sub is a UUID
        // and user.id is "1204", subscriberId should be matched against
        // userId, not sub.
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("3520324b-3035-4510-8fca-a8a18dbd1da2")  // KC UUID
            .claim("userId", 1204)  // numeric — guard accepts via String.valueOf
            .claim("realm_access", Map.of("roles", List.of("offline_access")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        assertThatCode(() -> guard.requireMatchOrThrow("1204")).doesNotThrowAnyException();
    }

    @Test
    void passesWhenJwtSubscriberIdClaimMatchesSubscriberId() {
        // subscriberId claim is the canonical FUTURE identifier (Faz 23.5 /
        // 24 hardening target). It takes priority over userId and sub so
        // future tokens automatically tighten the match without code change.
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("3520324b-3035-4510-8fca-a8a18dbd1da2")
            .claim("userId", 1204)
            .claim("subscriberId", "canon-77")  // canonical claim
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of())
        );

        // Match against canonical subscriberId claim, not userId.
        assertThatCode(() -> guard.requireMatchOrThrow("canon-77")).doesNotThrowAnyException();
    }

    @Test
    void throwsAccessDeniedWhenAllTrustedClaimsMismatch() {
        // None of subscriberId / userId / sub matches the input → 403.
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("alice-uuid")
            .claim("userId", "1204")
            .claim("subscriberId", "canon-77")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of())
        );

        assertThatThrownBy(() -> guard.requireMatchOrThrow("eve"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("no trusted JWT claim matches");
    }

    @Test
    void throwsWhenAllTrustedClaimsAreNull() {
        // No subscriberId / userId / sub → no match possible.
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("permissions", List.of("PERM_FOO"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of())
        );

        assertThatThrownBy(() -> guard.requireMatchOrThrow("alice"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("no trusted JWT claim matches");
    }

    @Test
    void throwsWhenSubscriberIdIsNull() {
        Jwt jwt = newJwt("alice");
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of())
        );

        assertThatThrownBy(() -> guard.requireMatchOrThrow(null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("subscriber identity unresolved");
    }

    @Test
    void throwsWhenSubscriberIdIsBlank() {
        Jwt jwt = newJwt("alice");
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of())
        );

        assertThatThrownBy(() -> guard.requireMatchOrThrow("   "))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("subscriber identity unresolved");
    }

    @Test
    void passesSilentlyWhenNoAuthenticationInContext() {
        // Slice tests run with addFilters=false; SecurityContext is empty.
        // Guard must not throw — slice tests would otherwise fail without
        // contortions that don't exercise the security boundary anyway.
        SecurityContextHolder.clearContext();

        assertThatCode(() -> guard.requireMatchOrThrow("alice")).doesNotThrowAnyException();
    }

    @Test
    void passesSilentlyWhenAuthenticationIsAnonymous() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
            "key",
            "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        SecurityContextHolder.getContext().setAuthentication(anon);

        // isAuthenticated() returns true for AnonymousAuthenticationToken;
        // but principal is a String, not a Jwt → guard treats as
        // permissive profile (slice-test) and passes silently.
        assertThatCode(() -> guard.requireMatchOrThrow("alice")).doesNotThrowAnyException();
    }

    @Test
    void passesSilentlyWhenPrincipalIsNotJwt() {
        // E.g. local profile with UsernamePasswordAuthenticationToken.
        UsernamePasswordAuthenticationToken upat = new UsernamePasswordAuthenticationToken(
            "alice", "irrelevant", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(upat);

        assertThatCode(() -> guard.requireMatchOrThrow("alice")).doesNotThrowAnyException();
    }

    // ----- Faz 23.5 hardening — Codex thread 019e0316 iter-3 absorb -----

    @Test
    void strictMode_subscriberIdOnly_rejectsLegacySubFallback() {
        // Operator-flipped strict configuration: only the canonical
        // `subscriberId` claim is trusted. Legacy `userId` / `sub` matches
        // become 403 — the cutover signal.
        SubscriberIdentityGuard strict = new SubscriberIdentityGuard(
            new NotifyConfig.SecurityConfig("default", List.of("subscriberId")),
            meterRegistry);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(newJwt("alice"), List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        assertThatThrownBy(() -> strict.requireMatchOrThrow("alice"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(meterRegistry.counter(
            SubscriberIdentityGuard.MATCH_METER, SubscriberIdentityGuard.CLAIM_TAG,
            SubscriberIdentityGuard.NO_MATCH_TAG).count()).isEqualTo(1.0);
    }

    @Test
    void strictMode_subscriberIdOnly_acceptsCanonicalClaim() {
        SubscriberIdentityGuard strict = new SubscriberIdentityGuard(
            new NotifyConfig.SecurityConfig("default", List.of("subscriberId")),
            meterRegistry);
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("uuid-1")
            .claim("subscriberId", "1204")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        assertThatCode(() -> strict.requireMatchOrThrow("1204")).doesNotThrowAnyException();
        assertThat(meterRegistry.counter(
            SubscriberIdentityGuard.MATCH_METER, SubscriberIdentityGuard.CLAIM_TAG,
            "subscriberId").count()).isEqualTo(1.0);
    }

    @Test
    void counter_taggedWithMatchedClaim_legacyCompat() {
        // Legacy default config matches via `userId` claim.
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("uuid-1")
            .claim("userId", 1204)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        guard.requireMatchOrThrow("1204");

        assertThat(meterRegistry.counter(
            SubscriberIdentityGuard.MATCH_METER, SubscriberIdentityGuard.CLAIM_TAG,
            "userId").count()).isEqualTo(1.0);
    }

    @Test
    void counter_notIncrementedOnBlankInput() {
        // Blank input goes to the 400 path before configured-claim
        // resolution; the metric should ignore it.
        Jwt jwt = newJwt("alice");
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        assertThatThrownBy(() -> guard.requireMatchOrThrow(""))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(meterRegistry.find(SubscriberIdentityGuard.MATCH_METER).counters())
            .isEmpty();
    }

    private static Jwt newJwt(String subject) {
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(subject)
            .claim("preferred_username", subject)
            .claim("realm_access", Map.of("roles", List.of("offline_access")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }
}
