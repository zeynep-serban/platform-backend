package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

/**
 * Test helper for constructing {@link NotifyOrgAccessGuard} instances
 * in slice / security tests (Faz 23.6 PR-A1).
 *
 * <p>Mirrors {@link SubscriberIdentityGuardTestSupport}: most slice
 * tests want a guard that behaves like the production single-tenant
 * default ("default" fallback). Specific cross-org tests can build a
 * config inline to opt out.
 */
final class NotifyOrgAccessGuardTestSupport {

    private NotifyOrgAccessGuardTestSupport() {
        // static-only
    }

    /**
     * @return a guard wired with the legacy single-tenant default-org
     * fallback ("default"), the canonical claim list, and a fresh
     * {@link SimpleMeterRegistry} (Faz 24 / PR-5.1 — Codex thread
     * `019e040c`: the production constructor now requires a
     * {@link MeterRegistry} for the
     * {@code notify.org.access.match} counter). Suitable for slice
     * tests that don't run a real {@code SecurityContext}.
     */
    static NotifyOrgAccessGuard newGuard() {
        return newGuard(new SimpleMeterRegistry());
    }

    /**
     * Variant that lets the caller pass its own {@link MeterRegistry}
     * so a test can assert which {@code source=} tag was incremented.
     */
    static NotifyOrgAccessGuard newGuard(MeterRegistry registry) {
        NotifyConfig config = new NotifyConfig(
            new NotifyConfig.DispatchConfig(false),
            new NotifyConfig.IntakeConfig(10000),
            new NotifyConfig.IdempotencyConfig(24),
            new NotifyConfig.DedupeConfig(5),
            new NotifyConfig.RetryConfig(5, 30000L, 2.5d, 3600000L, 0.25d),
            new NotifyConfig.AuditConfig(90, false, "0 0 2 * * *", 24, false, 3, true),
            new NotifyConfig.RedactionConfig("test-pepper"),
            new NotifyConfig.WorkerConfig(5000L, 25, 50, 60000L, ""),
            new NotifyConfig.SecurityConfig(
                "default",
                List.of("subscriberId", "userId", "sub")
            )
        );
        return new NotifyOrgAccessGuard(config, registry);
    }
}
