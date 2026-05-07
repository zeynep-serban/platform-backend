package com.serban.notify.api;

import com.serban.notify.config.NotifyConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

/**
 * Test helper for constructing {@link SubscriberIdentityGuard} instances
 * in slice / security tests.
 *
 * <p>Faz 23.5 hardening (Codex thread {@code 019e0316} iter-3 AGREE
 * absorb): the guard now requires a {@link NotifyConfig.SecurityConfig}
 * and a {@link MeterRegistry}, but most slice tests don't care about the
 * configured claim list or the match counter — they only need a guard
 * that behaves like the legacy default. This factory hands them one in a
 * single line.
 */
final class SubscriberIdentityGuardTestSupport {

    private SubscriberIdentityGuardTestSupport() {
        // static-only
    }

    static final List<String> DEFAULT_CLAIMS = List.of("subscriberId", "userId", "sub");

    /**
     * @return a guard wired with the legacy {@code [subscriberId, userId,
     * sub]} claim list and a fresh {@link SimpleMeterRegistry}; suitable
     * for slice tests that don't assert on the match counter.
     */
    static SubscriberIdentityGuard newGuard() {
        return new SubscriberIdentityGuard(
            new NotifyConfig.SecurityConfig("default", DEFAULT_CLAIMS),
            new SimpleMeterRegistry()
        );
    }

    /**
     * @return a guard wired with the supplied claim list. Use when a test
     * specifically asserts on strict mode behaviour.
     */
    static SubscriberIdentityGuard newGuardWith(List<String> claims) {
        return new SubscriberIdentityGuard(
            new NotifyConfig.SecurityConfig("default", List.copyOf(claims)),
            new SimpleMeterRegistry()
        );
    }
}
