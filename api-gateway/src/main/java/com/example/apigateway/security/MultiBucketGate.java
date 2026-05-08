package com.example.apigateway.security;

import java.util.List;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2 implementation
 * guard, iter-3 P1 #3 honest-naming absorb): SOFT dual-bucket gate.
 *
 * <p>Semantic contract (soft, NOT strong-atomic):
 * <ul>
 *   <li>Phase 1 — peek every bucket. If any is empty, deny without
 *       consuming any token. Strong here.</li>
 *   <li>Phase 2 — consume each bucket sequentially. Under
 *       contention, a concurrent caller can drain a bucket between
 *       phase 1 peek and phase 2 consume. In that case we deny but
 *       the buckets that DID consume forfeit their token. This is a
 *       soft throttle, not a bank transaction.</li>
 * </ul>
 *
 * <p>Why soft is OK here: HPA replicas already multiply the
 * effective threshold cluster-wide, and the rate limiter is a
 * dampener, not a security boundary. Forfeit-on-contention is
 * bounded by burst (typically 2-20 tokens per minute) and refills
 * within seconds. Stronger semantics (ordered locks, refund/
 * reservation) would add deadlock risk for marginal benefit.
 *
 * <p>The naive single-line {@code A || B} composition is broken
 * because A's token is consumed even when B denies; this gate
 * prevents that common case via the explicit phase-1 peek. The
 * remaining contention-window forfeit is the documented compromise.
 */
public final class MultiBucketGate {

  private MultiBucketGate() {
    // utility class
  }

  /**
   * Soft-atomically tries to acquire one token from each bucket in
   * the list. Returns a decision indicating whether all buckets had
   * a token AND were successfully consumed.
   *
   * <p>SOFT semantics (Codex iter-3 P1 #3 absorb): denial in phase 1
   * (peek) consumes nothing. Denial in phase 2 (consume) under
   * contention may forfeit some buckets' tokens — see class javadoc.
   *
   * <p>{@code null} entries in the list are ignored (e.g. when the
   * fingerprint bucket is absent because no Authorization header
   * was provided).
   */
  public static Decision tryAcquireAll(List<TokenBucket> buckets, long nowNanos) {
    long maxRetryAfter = 0;

    // Phase 1 — peek all buckets. If any is empty, deny without
    // consuming anything; record the max retry-after for the caller.
    for (TokenBucket bucket : buckets) {
      if (bucket == null) {
        continue;
      }
      if (!bucket.tryPeek(nowNanos)) {
        long retry = bucket.retryAfterSeconds(nowNanos);
        if (retry > maxRetryAfter) {
          maxRetryAfter = retry;
        }
      }
    }
    if (maxRetryAfter > 0) {
      return Decision.deny(maxRetryAfter);
    }

    // Phase 2 — consume all. A concurrent caller may have already
    // taken a token between phase 1 and phase 2; in that case we
    // accept the partial-allow risk (one of the buckets succeeds)
    // and rollback by NOT consuming anything else. This is bounded
    // because buckets refill quickly (perMinute / 60 per second).
    boolean allConsumed = true;
    long consumedCount = 0;
    for (TokenBucket bucket : buckets) {
      if (bucket == null) {
        continue;
      }
      if (bucket.consumeOne(nowNanos)) {
        consumedCount += 1;
      } else {
        allConsumed = false;
        break;
      }
    }

    if (allConsumed) {
      return Decision.allow();
    }

    // Partial consume on contention — caller is denied. The
    // already-consumed tokens are forfeit (will refill). For a soft
    // throttle this is acceptable; the alternative (refunding) needs
    // a more complex API and adds little benefit for this use case.
    return Decision.deny(1L);
  }

  public record Decision(boolean allowed, long retryAfterSeconds) {
    public static Decision allow() {
      return new Decision(true, 0L);
    }

    public static Decision deny(long retryAfterSeconds) {
      return new Decision(false, retryAfterSeconds);
    }
  }
}
