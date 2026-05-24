package com.serban.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Notify config — application.yml `notify.*` keys (Faz 23.1 PR2 + ileri sub-PR'lar).
 *
 * <p>Codex 019df9ae Q3 PARTIAL absorb:
 * <ul>
 *   <li>{@code dispatch.enabled=false} default — worker yok, intake-only mode</li>
 *   <li>{@code intake.maxPending} bounded intake — eşik aşılınca submit 429/503</li>
 *   <li>Admin reset-queue endpoint **YASAK** (Codex non-neg)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "notify")
@Validated
public record NotifyConfig(
    @NotNull DispatchConfig dispatch,
    @NotNull IntakeConfig intake,
    @NotNull IdempotencyConfig idempotency,
    @NotNull DedupeConfig dedupe,
    @NotNull RetryConfig retry,
    @NotNull AuditConfig audit,
    @NotNull RedactionConfig redaction,
    @NotNull WorkerConfig worker,
    @NotNull SecurityConfig security,
    @NotNull KvkkConfig kvkk
) {

    public record DispatchConfig(
        @DefaultValue("false") boolean enabled
    ) {}

    public record IntakeConfig(
        @Min(1) @DefaultValue("10000") int maxPending
    ) {}

    public record IdempotencyConfig(
        @Min(1) @DefaultValue("24") int windowHours
    ) {}

    public record DedupeConfig(
        @Min(1) @DefaultValue("5") int windowMinutes
    ) {}

    public record RetryConfig(
        @Min(1) @DefaultValue("5") int maxAttempts,
        @Min(1000) @DefaultValue("30000") long backoffInitialMs,
        @DefaultValue("2.5") double backoffMultiplier,
        @Min(1000) @DefaultValue("3600000") long maxBackoffMs,
        @DefaultValue("0.25") double jitterRatio
    ) {}

    /**
     * PR-D.1 audit config (Codex 019dfdec Q5 absorb — retention scheduled task).
     *
     * @param retentionDays how many days back to keep partitions (default 90)
     * @param retentionEnabled @ConditionalOnProperty gate for AuditPartitionRetentionService
     * @param retentionCron cron expression (default daily 02:00 UTC)
     * @param retentionGraceHours detach → drop grace window (default 24h)
     * @param retentionDryRun log only, no DETACH/DROP (default false; ops verification)
     * @param retentionFutureMonths idempotent ensure: current + N future month partitions
     * @param retentionSchedulingEnabled @Scheduled tick guard (test isolation pattern;
     *                                    matches WorkerConfig.scheduling-enabled)
     */
    public record AuditConfig(
        @Min(1) @DefaultValue("90") int retentionDays,
        @DefaultValue("false") boolean retentionEnabled,
        @DefaultValue("0 0 2 * * *") String retentionCron,
        @Min(1) @DefaultValue("24") int retentionGraceHours,
        @DefaultValue("false") boolean retentionDryRun,
        @Min(1) @DefaultValue("3") int retentionFutureMonths,
        @DefaultValue("true") boolean retentionSchedulingEnabled
    ) {}

    public record RedactionConfig(
        /**
         * HMAC pepper for recipient hash (Codex Q4 REVISE absorb).
         * Faz 23.1 PR2: ENV var fallback (dev). Faz 23.2 production: Vault/ESO injection.
         * Empty pepper allowed in dev profile — deterministic test reproducibility.
         */
        @DefaultValue("dev-only-pepper-not-for-production") String pepper
    ) {}

    /**
     * PR4 worker config (Codex 019dfa47 Q2 PARTIAL absorb — config-driven cadence).
     *
     * @param pollDelayMs OutboxPoller and RetryWorker fixedDelay between cycles
     * @param intentBatchSize max intents claimed per OutboxPoller cycle
     * @param retryBatchSize max deliveries claimed per RetryWorker cycle
     * @param leaseDurationMs lease deadline = now + leaseDurationMs at claim time
     * @param owner pod identifier (default: derived from hostname-pid at startup)
     */
    public record WorkerConfig(
        @Min(100) @DefaultValue("5000") long pollDelayMs,
        @Min(1) @DefaultValue("25") int intentBatchSize,
        @Min(1) @DefaultValue("50") int retryBatchSize,
        @Min(1000) @DefaultValue("60000") long leaseDurationMs,
        @DefaultValue("") String owner
    ) {}

    /**
     * Security knobs for the notification surface.
     *
     * <h3>{@code defaultOrgId}</h3>
     *
     * <p>Single-tenant default-org fallback for {@link
     * com.serban.notify.api.NotifyOrgAccessGuard}. Resolve order is claim
     * &gt; tenant &gt; allowed_orgs &gt; {@code defaultOrgId}. The default-org
     * fallback applies <i>only</i> when {@code requestedOrgId ==
     * defaultOrgId} — it is not a wildcard (Codex thread {@code 019e0289}
     * iter-3 AGREE absorb). Set this to an empty string to disable the
     * fallback in multi-tenant deployments.
     *
     * <h3>{@code subscriberIdentityClaims}</h3>
     *
     * <p>Faz 23.5 hardening (Codex thread {@code 019e0316} iter-3 AGREE):
     * the JWT claim names {@link
     * com.serban.notify.api.SubscriberIdentityGuard} accepts as carrying
     * the subscriber identifier, in priority order. The default
     * {@code [subscriberId, userId, sub]} keeps legacy tokens working
     * while the canonical {@code subscriberId} mapper rolls out. Operators
     * flip to {@code subscriberId} alone via
     * {@code NOTIFY_SECURITY_SUBSCRIBER_IDENTITY_CLAIMS=subscriberId} once
     * the metric {@code notify_subscriber_identity_match_total} shows the
     * legacy {@code userId/sub} match rate has dropped to zero.
     *
     * <p>The {@link Pattern} restricts the input to the three known claim
     * names — typos in the env override fail boot rather than silently
     * disabling the guard, and the metric tag cardinality stays bounded
     * at four ({@code subscriberId}, {@code userId}, {@code sub},
     * {@code none}).
     *
     * <h3>{@code subscriberIdentityStrict}</h3>
     *
     * <p>Faz 23.6 PR-5.5 cutover toggle (Codex thread {@code 019e07d6}
     * iter-1 PARTIAL absorb). When {@code true} the {@link
     * com.serban.notify.api.SubscriberIdentityGuard} fail-closes for
     * requests that arrive without an authenticated JWT principal —
     * either no {@link org.springframework.security.core.Authentication}
     * in the {@code SecurityContextHolder} at all, or a non-{@code Jwt}
     * principal (anonymous, username/password). Default {@code false}
     * preserves the legacy silent-pass branch that {@code @WebMvcTest
     * (addFilters = false)} slice tests rely on; production overlays
     * flip the env to {@code true} once the upstream filter chain is
     * confirmed to inject a {@code JwtAuthenticationToken} on every
     * route the guard protects.
     */
    public record SecurityConfig(
        @DefaultValue("default") String defaultOrgId,
        @NotEmpty
        @DefaultValue({"subscriberId", "userId", "sub"})
        List<@Pattern(regexp = "subscriberId|userId|sub") String> subscriberIdentityClaims,
        @DefaultValue("false") boolean subscriberIdentityStrict
    ) {
        // Codex iter-1 absorb originally suggested a two-arg overload to
        // keep legacy test fixtures compiling unchanged, but adding a
        // second public constructor on a record breaks Spring Boot's
        // @ConfigurationProperties auto-binding (the binder cannot pick
        // a canonical constructor when two are visible and Boot 3.x
        // record binding rejects the prefix with `Field error ... on
        // field 'security': rejected value [null]`). The CI Testcontainers
        // PG test caught this on PR #126 head 2f51f7d. Fix: drop the
        // overload, update every test fixture to the three-arg form
        // (passing `false` to preserve legacy silent-pass behaviour).
    }

    /**
     * Faz 23.2.B PR-K6 — Tenant-Scoped DPO Authz config (Codex thread
     * {@code 019e59ea} iter-3 AGREE absorb).
     *
     * <h3>{@code dpoAuthzEnabled}</h3>
     *
     * <p>Per-tenant DPO (Data Protection Officer) authorization gate on
     * {@code AdminErasureController}. When {@code true}, the existing
     * {@code ROLE_PRIVACY_OFFICER} + {@code NotifyOrgAccessGuard} stack is
     * augmented with an OpenFGA tuple check
     * {@code organization:<orgId>#can_erasure@user:<numericId>}.
     *
     * <p>Default {@code false} for backward-compat: in environments where
     * the OpenFGA model has not yet been extended with the {@code dpo}
     * relation, or DPO tuples have not been seeded, the legacy role+org
     * stack stays primary. Operator flips per overlay once:
     * <ol>
     *   <li>OpenFGA model {@code 01KS8QE…} or successor includes
     *       {@code organization#can_erasure: [user] or dpo or admin}</li>
     *   <li>{@code organization:<id>#dpo@user:<id>} tuples seeded per org</li>
     *   <li>Test overlay burn-in shows no unexpected 403 from
     *       {@code DpoAuthzService}</li>
     * </ol>
     *
     * <p>Fail-closed: when enabled, missing claim or unreachable
     * permission-service ⇒ 403 (NOT default-allow).
     *
     * <h3>{@code dpoUserIdClaims}</h3>
     *
     * <p>Bounded JWT claim allowlist for resolving the OpenFGA
     * {@code user:<numeric-id>} identity. Codex iter-2 absorb: the JWT
     * {@code sub} claim is the Keycloak UUID, while OpenFGA tuples are
     * written with the numeric DB user id (see
     * {@code AuthorizationControllerV1} historical comment). Resolver
     * scans the configured claim names in order and returns the first
     * non-blank value; {@code sub} fallback is intentionally NOT
     * permitted to prevent silent tuple-namespace mismatch.
     *
     * <p>Default {@code [userId, uid]} — {@code userId} is the canonical
     * frontend-injected claim from auth-service / permission-service;
     * {@code uid} is a documented legacy fallback. Operators tightening
     * the contract set
     * {@code NOTIFY_KVKK_DPO_USER_ID_CLAIMS=userId} once tokens always
     * carry the canonical claim.
     *
     * <p>The {@link Pattern} restricts inputs to the four known names —
     * typos in env override fail boot rather than silently disabling the
     * resolver (matches the {@code subscriberIdentityClaims} pattern).
     */
    public record KvkkConfig(
        @DefaultValue("false") boolean dpoAuthzEnabled,
        @NotEmpty
        @DefaultValue({"userId", "uid"})
        List<@Pattern(regexp = "userId|uid|user_id|username") String> dpoUserIdClaims
    ) {}
}
