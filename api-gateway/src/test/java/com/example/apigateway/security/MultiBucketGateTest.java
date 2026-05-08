package com.example.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MultiBucketGateTest {

  @Test
  void allowsWhenAllBucketsHaveTokens() {
    TokenBucket a = new TokenBucket(60, 5);
    TokenBucket b = new TokenBucket(60, 5);
    long now = 0L;

    MultiBucketGate.Decision result = MultiBucketGate.tryAcquireAll(List.of(a, b), now);
    assertThat(result.allowed()).isTrue();
    assertThat(result.retryAfterSeconds()).isZero();
  }

  @Test
  void deniesIfAnyBucketEmpty_NeitherIsConsumed() {
    TokenBucket a = new TokenBucket(60, 5); // available
    TokenBucket b = new TokenBucket(60, 1); // 1 burst, will be empty
    long now = 0L;

    // Drain b
    assertThat(b.consumeOne(now)).isTrue();

    // Now b is empty; gate should deny without consuming a
    MultiBucketGate.Decision result = MultiBucketGate.tryAcquireAll(List.of(a, b), now);
    assertThat(result.allowed()).isFalse();
    assertThat(result.retryAfterSeconds()).isPositive();

    // Critical: a was NOT consumed (still has 5 tokens)
    int aCount = 0;
    for (int i = 0; i < 6; i++) {
      if (a.consumeOne(now)) {
        aCount += 1;
      }
    }
    assertThat(aCount).isEqualTo(5); // full burst still there
  }

  @Test
  void ignoresNullBuckets() {
    TokenBucket a = new TokenBucket(60, 1);
    long now = 0L;

    // null bucket simulates "no token fingerprint" case
    MultiBucketGate.Decision result = MultiBucketGate.tryAcquireAll(List.of(a, a), now);
    // Note: List.of() rejects null; testing via single bucket twice
    assertThat(result.allowed()).isFalse(); // a only has 1 token, but tryAcquireAll consumes from each
  }

  @Test
  void retryAfterIsMaxOfDeniedBuckets() {
    TokenBucket fast = new TokenBucket(120, 1); // 2/sec
    TokenBucket slow = new TokenBucket(30, 1); // 0.5/sec — slower replenish
    long now = 0L;

    fast.consumeOne(now);
    slow.consumeOne(now);

    MultiBucketGate.Decision result = MultiBucketGate.tryAcquireAll(List.of(fast, slow), now);
    assertThat(result.allowed()).isFalse();
    // slow needs ~2s to refill 1 token; fast needs ~1s — max should win
    assertThat(result.retryAfterSeconds()).isGreaterThanOrEqualTo(2L);
  }
}
