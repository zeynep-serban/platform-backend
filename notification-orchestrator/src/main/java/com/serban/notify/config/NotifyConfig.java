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
    @NotNull RedactionConfig redaction
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
        @DefaultValue("2.5") double backoffMultiplier
    ) {}

    public record AuditConfig(
        @Min(1) @DefaultValue("90") int retentionDays
    ) {}

    public record RedactionConfig(
        /**
         * HMAC pepper for recipient hash (Codex Q4 REVISE absorb).
         * Faz 23.1 PR2: ENV var fallback (dev). Faz 23.2 production: Vault/ESO injection.
         * Empty pepper allowed in dev profile — deterministic test reproducibility.
         */
        @DefaultValue("dev-only-pepper-not-for-production") String pepper
    ) {}
}
