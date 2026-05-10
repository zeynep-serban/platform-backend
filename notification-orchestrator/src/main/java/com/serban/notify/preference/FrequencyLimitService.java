package com.serban.notify.preference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FrequencyLimitService — per-user **fixed-window** rate limiter
 * (T1.1.7, Faz 23.2.A acceptance gate).
 *
 * <p>**Window semantics**: not a true sliding window — a per-key fixed
 * window starting from first send. Window rolls (counter resets) when
 * elapsed time reaches the configured `windowMillis`. Codex iter-1
 * (thread `019e1199`) REVISE absorb: contract clarified as fixed-window
 * soft limiter (not a sliding window), matching {@link
 * com.serban.notify.abuse.AbuseGuardService} pattern.
 *
 * <p>Per-(orgId, subscriberId) check. Uses
 * {@code SubscriberPreference.frequencyLimitPerDay} column as the limit
 * (24-hour window). When the count of recorded sends within the window
 * reaches the limit, subsequent {@link #checkAndRecord} calls return false
 * (suppression with "frequency_limit" reason at PreferenceService level).
 *
 * <p>**In-memory implementation** (multi-pod soft enforcement, like
 * AbuseGuardService): single-pod windows lose state on restart; multi-pod
 * deployment effective limit ≈ pod_count × per_pod_limit. Strict
 * enforcement requires PG-backed counter or Redis (deferred — see R14).
 *
 * <p>**Thread safety**: per-key {@code synchronized} block covers the
 * window-roll + increment + limit-check + decrements-back path
 * atomically. Codex iter-1 absorb: single-mutex per state eliminates
 * the rollover race where a thread could decrement into negative
 * after another thread resets the window.
 */
@Service
public class FrequencyLimitService {

    private static final Logger log = LoggerFactory.getLogger(FrequencyLimitService.class);

    /** Fixed-window key — (orgId, subscriberId) tuple. */
    private record WindowKey(String orgId, String subscriberId) {}

    /** Fixed-window value — current count + window start (epoch ms). */
    private static class WindowState {
        final AtomicLong count = new AtomicLong(0);
        volatile long windowStartMs;

        WindowState(long now) {
            this.windowStartMs = now;
        }
    }

    private final ConcurrentHashMap<WindowKey, WindowState> windows = new ConcurrentHashMap<>();
    private final long windowMillis;

    /**
     * Clock for deterministic test timing (T1.1.7).
     * Default {@link Clock#systemDefaultZone()}; tests override via setter.
     */
    private Clock clock = Clock.systemDefaultZone();

    public FrequencyLimitService(
        @Value("${notify.preferences.frequency-limit.window-hours:24}") long windowHours
    ) {
        this.windowMillis = windowHours * 3600L * 1000L;
        log.info("FrequencyLimitService initialized: window={}h (in-memory soft enforcement)",
            windowHours);
    }

    /**
     * Test-only setter — production keeps default systemDefaultZone clock.
     * Codex review pattern (matches SubscriberPreferenceService.setClock).
     */
    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    /**
     * Check if a send is within the configured frequency limit; record send.
     *
     * <p>Atomic: increments counter then compares to limit. If new count
     * exceeds limit, returns false (suppress). Counter is decremented back
     * on suppression so that the limit acts like a max-per-window cap
     * rather than max-attempts-per-window.
     *
     * <p>Window roll: if elapsed >= window, resets counter to 1 (this
     * send) + window start to now.
     *
     * @param orgId          tenant scope
     * @param subscriberId   user identity (subscriber)
     * @param limit          max sends per window from preference (null/<=0 → no limit)
     * @return true if within limit (allow); false if would exceed (deny)
     */
    public boolean checkAndRecord(String orgId, String subscriberId, Integer limit) {
        if (limit == null || limit <= 0) {
            return true;  // no limit configured → always allow
        }

        long now = clock.instant().toEpochMilli();
        WindowKey key = new WindowKey(orgId, subscriberId);

        WindowState state = windows.computeIfAbsent(key, k -> new WindowState(now));

        // Codex iter-1 (019e1199) REVISE absorb: window-roll + increment +
        // limit-check + decrements-back must be one atomic critical section
        // per-key. Without single-mutex coverage a thread could:
        //   (a) increment over limit → decision deny
        //   (b) another thread resets window between deny+decrement
        //   (c) deny thread's decrement subtracts from new window → negative
        synchronized (state) {
            // Window roll if expired
            if (now - state.windowStartMs >= windowMillis) {
                state.count.set(0);
                state.windowStartMs = now;
            }

            long newCount = state.count.incrementAndGet();
            if (newCount > limit) {
                // Decrement back inside same critical section — no rollover race
                state.count.decrementAndGet();
                log.debug("frequency_limit deny: orgId={} subscriberId={} count={}+1 limit={}",
                    orgId, subscriberId, newCount - 1, limit);
                return false;
            }
            return true;
        }
    }

    /**
     * Test-only: clear all windows.
     */
    public void clearAll() {
        windows.clear();
    }
}
