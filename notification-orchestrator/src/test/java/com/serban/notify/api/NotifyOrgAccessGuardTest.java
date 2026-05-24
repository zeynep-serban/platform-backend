package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    private static final List<String> DEFAULT_CLAIMS =
        List.of("subscriberId", "userId", "sub");

    private static final NotifyConfig.SecurityConfig DEFAULT_SECURITY =
        new NotifyConfig.SecurityConfig("default", DEFAULT_CLAIMS, false);

    private static final NotifyConfig.SecurityConfig EMPTY_SECURITY =
        new NotifyConfig.SecurityConfig("", DEFAULT_CLAIMS, false);

    // Faz 24 / PR-5.1: guards + registry are built per-test in a single
    // @BeforeEach because the constructor needs the per-test
    // MeterRegistry. Field initializers and a separate @BeforeEach
    // would race on null state.
    private NotifyOrgAccessGuard guardWithDefault;
    private NotifyOrgAccessGuard guardWithoutDefault;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUpGuardsAndRegistry() {
        meterRegistry = new SimpleMeterRegistry();
        guardWithDefault = guard(DEFAULT_SECURITY);
        guardWithoutDefault = guard(EMPTY_SECURITY);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Helper for the new PR-5.1 metric assertions — returns the count
     * for the given source tag, or 0 if no counter was recorded.
     */
    private double matchCount(String source) {
        var counter = meterRegistry.find(NotifyOrgAccessGuard.MATCH_METER)
            .tag(NotifyOrgAccessGuard.SOURCE_TAG, source)
            .counter();
        return counter == null ? 0.0d : counter.count();
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
            security,
            new NotifyConfig.KvkkConfig(false, List.of("userId", "uid"))
        );
        // Faz 24 / PR-5.1 (Codex thread `019e040c` PARTIAL iter-1):
        // pass the per-test MeterRegistry so each test can assert
        // which `source=` tag was incremented.
        return new NotifyOrgAccessGuard(config, meterRegistry);
    }

    @Test
    void claim_priority_subscriberIdWinsOverUserIdAndSub() {
        // Verifies the org guard does not depend on the subscriber-identity
        // claim configuration — sanity check that this delta did not cross
        // wires between the two guards.
        setJwt(Map.of(
            "org_id", "tenant-from-claim",
            "subscriberId", "1204"
        ));
        guardWithoutDefault.requireOrgAccessOrThrow("tenant-from-claim");
    }

    // ─── Faz 24 / PR-5.1 metric instrumentation tests (Codex 019e040c) ────

    @Test
    void metric_org_id_match_incrementsOrgIdSourceTag() {
        // Single-tenant principal with `org_id` claim → counter
        // must increment under source="org_id" and zero everywhere else.
        setJwt(Map.of("org_id", "tenant-claim"));
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("tenant-claim");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ORG_ID)).isEqualTo(1.0d);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_TENANT_ID)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ALLOWED_ORGS)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_DEFAULT)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_NONE)).isZero();
    }

    @Test
    void metric_tenant_id_match_incrementsTenantIdSourceTag() {
        // Tenant-platform alias claim — `tenant_id` resolves when
        // `org_id` is absent.
        setJwt(Map.of("tenant_id", "tenant-claim"));
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("tenant-claim");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_TENANT_ID)).isEqualTo(1.0d);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ORG_ID)).isZero();
    }

    @Test
    void metric_allowed_orgs_match_incrementsAllowedOrgsSourceTag() {
        // Multi-tenant operator with `allowed_orgs` array — third
        // priority after `org_id` and `tenant_id`.
        setJwt(Map.of("allowed_orgs", List.of("tenant-claim", "other-tenant")));
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("tenant-claim");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ALLOWED_ORGS)).isEqualTo(1.0d);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ORG_ID)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_TENANT_ID)).isZero();
    }

    @Test
    void metric_default_fallback_incrementsDefaultSourceTag() {
        // No claim — falls through to the configured default-org-id
        // when requested == default. THIS IS THE METRIC THAT THE
        // PR-5.5 STRICT CUTOVER GATE WATCHES: when this counter
        // sits at zero for the observation window, the env flip
        // (NOTIFY_SECURITY_DEFAULT_ORG_ID="") is safe.
        setJwt(Map.of("subscriberId", "1204"));  // unrelated claim
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("default");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_DEFAULT)).isEqualTo(1.0d);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_NONE)).isZero();
    }

    @Test
    void metric_cross_org_authority_incrementsCrossOrgSourceTag() {
        // Forward-compat bypass — emitted but not seeded in v1
        // catalogue. Still tracked so we can see if any caller
        // ever uses it.
        setJwt(Map.of("subscriberId", "ops-bot"), "notify-deliveries-cross-org");
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("any-tenant");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_CROSS_ORG)).isEqualTo(1.0d);
    }

    @Test
    void metric_no_match_incrementsNoneTagBeforeAccessDeniedException() {
        // Negative path — no claim matches, default fallback also
        // misses (requested != default). The `none` counter MUST
        // increment BEFORE the throw so the cutover gate in PR-5.4
        // catches the legitimate strict-403 cases. Non-zero `none`
        // blocks the cutover.
        setJwt(Map.of("subscriberId", "1204"));  // no org claim
        assertThatThrownBy(() ->
            guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("foreign-tenant"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_NONE)).isEqualTo(1.0d);
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ORG_ID)).isZero();
    }

    @Test
    void metric_silent_pass_does_not_increment_counter_in_slice_test() {
        // No SecurityContext authentication — the guard silently
        // passes (slice-test pattern). No counter should fire
        // because there was no resolution attempt; this avoids
        // contaminating the cutover gate with test-only traffic.
        guard(DEFAULT_SECURITY).requireOrgAccessOrThrow("tenant-claim");
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_ORG_ID)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_DEFAULT)).isZero();
        assertThat(matchCount(NotifyOrgAccessGuard.SOURCE_NONE)).isZero();
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
