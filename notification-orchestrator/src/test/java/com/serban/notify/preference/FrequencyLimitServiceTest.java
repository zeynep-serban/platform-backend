package com.serban.notify.preference;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrequencyLimitService direct unit tests (T1.1.7 Codex iter-1
 * thread `019e1199` REVISE absorb — direct coverage for rollover,
 * isolation, concurrent boundary, repeated denials).
 */
class FrequencyLimitServiceTest {

    @Test
    void rolloverResetsCounter() {
        FrequencyLimitService svc = new FrequencyLimitService(1);  // 1 hour window

        Instant t0 = Instant.parse("2026-05-10T12:00:00Z");
        svc.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        // Fill window with 3 sends, limit=3
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();

        // 4th deny
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isFalse();

        // Roll forward beyond window (1h+1m later)
        Instant t1 = t0.plusSeconds(3700);
        svc.setClock(Clock.fixed(t1, ZoneOffset.UTC));

        // Counter reset — next 3 ALLOW again
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();

        // 4th deny again
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isFalse();
    }

    @Test
    void multiTenantIsolation() {
        FrequencyLimitService svc = new FrequencyLimitService(24);

        // Different orgs same subscriberId → independent windows
        for (int i = 0; i < 5; i++) {
            assertThat(svc.checkAndRecord("orgA", "user-1", 5)).isTrue();
            assertThat(svc.checkAndRecord("orgB", "user-1", 5)).isTrue();
        }
        // 6th deny per-org
        assertThat(svc.checkAndRecord("orgA", "user-1", 5)).isFalse();
        assertThat(svc.checkAndRecord("orgB", "user-1", 5)).isFalse();
    }

    @Test
    void perSubscriberIsolation() {
        FrequencyLimitService svc = new FrequencyLimitService(24);

        // Same org, different subscribers → independent windows
        for (int i = 0; i < 3; i++) {
            assertThat(svc.checkAndRecord("default", "user-A", 3)).isTrue();
            assertThat(svc.checkAndRecord("default", "user-B", 3)).isTrue();
        }
        assertThat(svc.checkAndRecord("default", "user-A", 3)).isFalse();
        assertThat(svc.checkAndRecord("default", "user-B", 3)).isFalse();
    }

    @Test
    void repeatedDenialsPreserveCount() {
        FrequencyLimitService svc = new FrequencyLimitService(24);

        // Hit limit
        for (int i = 0; i < 3; i++) {
            assertThat(svc.checkAndRecord("default", "user-1", 3)).isTrue();
        }

        // 100 denials in a row — count should NOT drop below limit
        for (int i = 0; i < 100; i++) {
            assertThat(svc.checkAndRecord("default", "user-1", 3))
                .as("denial #" + i + " preserves count").isFalse();
        }

        // Verify still at limit (4th allow attempt fails)
        assertThat(svc.checkAndRecord("default", "user-1", 3)).isFalse();
    }

    @Test
    void concurrentBoundaryAroundLimit() throws Exception {
        FrequencyLimitService svc = new FrequencyLimitService(24);

        // Limit = 50; 100 concurrent threads attempt — exactly 50 should ALLOW
        final int threads = 100;
        final int limit = 50;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (svc.checkAndRecord("default", "user-conc", limit)) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(allowed.get())
            .as("concurrent boundary: exactly limit threads should ALLOW (synchronized critical section)")
            .isEqualTo(limit);
    }

    @Test
    void nullOrZeroLimitAlwaysAllows() {
        FrequencyLimitService svc = new FrequencyLimitService(24);

        for (int i = 0; i < 1000; i++) {
            assertThat(svc.checkAndRecord("default", "user-1", null)).isTrue();
            assertThat(svc.checkAndRecord("default", "user-1", 0)).isTrue();
            assertThat(svc.checkAndRecord("default", "user-1", -5)).isTrue();
        }
    }
}
