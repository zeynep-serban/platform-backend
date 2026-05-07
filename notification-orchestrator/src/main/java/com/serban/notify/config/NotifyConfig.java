package com.serban.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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
    @NotNull SecurityConfig security
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
     * Faz 23.5 PR6 security knob — single-tenant default org fallback for
     * {@code NotifyOrgAccessGuard}.
     *
     * <p>Resolve order claim &gt; tenant &gt; allowed_orgs &gt;
     * {@code defaultOrgId}. Default-org fallback yalnızca
     * {@code requestedOrgId == defaultOrgId} eşitliğinde geçerli; "her org'a
     * izin" semantiği YASAK (Codex 019e0289 iter-3 AGREE absorb).
     *
     * <p>{@code defaultOrgId} boş bırakılırsa fallback devre dışı; multi-tenant
     * deployment'larda her zaman JWT claim'lerinden çözülmesi zorlanır.
     */
    public record SecurityConfig(
        @DefaultValue("default") String defaultOrgId
    ) {}
}
