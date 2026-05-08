package com.example.apigateway.security;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 2 PR-BE-7: lock-free token bucket. Each request attempts to
 * consume one token; if the bucket is empty, the call returns false
 * (denied). Tokens replenish over time at a steady rate up to
 * {@code burst} capacity.
 *
 * <p>Thread-safe via {@code AtomicReference} CAS — multiple
 * concurrent {@code tryAcquire} callers race for a token but only
 * one succeeds per available token. CAS retry is bounded (each
 * iteration recomputes the current state from the live monotonic
 * clock) so spinning is naturally rate-limited.
 *
 * <p>Atomicity contract: {@code tryPeek()} reports whether a token
 * IS available; {@code consumeOne()} consumes exactly one. Composite
 * "all-or-nothing across N buckets" is implemented by
 * {@link MultiBucketGate#tryAcquireAll}.
 */
public final class TokenBucket {

  private final double tokensPerSecond;
  private final double burst;
  private final AtomicReference<State> state;

  public TokenBucket(int perMinute, int burst) {
    if (perMinute <= 0 || burst <= 0) {
      throw new IllegalArgumentException(
          "perMinute and burst must be positive: perMinute=" + perMinute + ", burst=" + burst);
    }
    this.tokensPerSecond = perMinute / 60.0;
    this.burst = burst;
    // lastNanos starts at Long.MIN_VALUE/2 so the first observation
    // (any positive nowNanos) replenishes a full burst from a long
    // distant past. This makes the bucket consistent across test
    // (arbitrary clock) and production (System.nanoTime) callers.
    this.state = new AtomicReference<>(new State(burst, Long.MIN_VALUE / 2));
  }

  /**
   * Returns true iff the bucket currently has at least one token
   * available (after replenishment up to {@code now}). Does NOT
   * consume — pair with {@link #consumeOne(long)} for atomic
   * acquisition.
   */
  public boolean tryPeek(long nowNanos) {
    State current = replenish(state.get(), nowNanos);
    return current.tokens >= 1.0;
  }

  /**
   * Atomically consumes one token if available. Returns true on
   * success, false if the bucket is empty.
   */
  public boolean consumeOne(long nowNanos) {
    while (true) {
      State current = state.get();
      State replenished = replenish(current, nowNanos);
      if (replenished.tokens < 1.0) {
        // Still update the timestamp so consecutive calls don't all
        // re-replenish from the same baseline; CAS so we don't clobber
        // a parallel update.
        state.compareAndSet(current, replenished);
        return false;
      }
      State next = new State(replenished.tokens - 1.0, replenished.lastNanos);
      if (state.compareAndSet(current, next)) {
        return true;
      }
      // CAS lost — retry with fresh state
    }
  }

  /**
   * Returns the wall-clock seconds the caller should wait before the
   * next token is expected to be available. Used for the
   * {@code Retry-After} header on 429 responses.
   */
  public long retryAfterSeconds(long nowNanos) {
    State current = replenish(state.get(), nowNanos);
    if (current.tokens >= 1.0) {
      return 0;
    }
    double tokensNeeded = 1.0 - current.tokens;
    double secondsNeeded = tokensNeeded / tokensPerSecond;
    return (long) Math.ceil(secondsNeeded);
  }

  private State replenish(State current, long nowNanos) {
    long elapsedNanos = nowNanos - current.lastNanos;
    if (elapsedNanos <= 0) {
      return current;
    }
    double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
    double newTokens = Math.min(burst, current.tokens + elapsedSeconds * tokensPerSecond);
    return new State(newTokens, nowNanos);
  }

  private record State(double tokens, long lastNanos) {}
}
