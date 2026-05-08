package com.example.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

  @Test
  void initialBurstAvailable() {
    TokenBucket bucket = new TokenBucket(60, 5); // 1/sec, burst 5
    long now = 0L;
    for (int i = 0; i < 5; i++) {
      assertThat(bucket.consumeOne(now)).as("token %d should be available", i).isTrue();
    }
    assertThat(bucket.consumeOne(now)).as("burst exhausted").isFalse();
  }

  @Test
  void replenishesAfterTime() {
    TokenBucket bucket = new TokenBucket(60, 1); // 1/sec, burst 1
    long t0 = 1_000_000_000L;
    assertThat(bucket.consumeOne(t0)).isTrue();
    assertThat(bucket.consumeOne(t0)).isFalse(); // empty

    long t1 = t0 + 2_000_000_000L; // +2s, well past 1 token
    assertThat(bucket.consumeOne(t1)).isTrue();
  }

  @Test
  void retryAfterReportsSecondsUntilNext() {
    TokenBucket bucket = new TokenBucket(60, 1); // 1/sec
    long t0 = 1_000_000_000L;
    bucket.consumeOne(t0); // empty bucket

    // 0s elapsed, need 1 token at 1/sec → ~1s wait
    long retry = bucket.retryAfterSeconds(t0);
    assertThat(retry).isEqualTo(1L);
  }

  @Test
  void tryPeekDoesNotConsume() {
    TokenBucket bucket = new TokenBucket(60, 1);
    long now = 0L;
    assertThat(bucket.tryPeek(now)).isTrue();
    assertThat(bucket.tryPeek(now)).isTrue(); // still 1
    assertThat(bucket.consumeOne(now)).isTrue();
    assertThat(bucket.tryPeek(now)).isFalse();
  }

  @Test
  void rejectsInvalidConfig() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TokenBucket(0, 5))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TokenBucket(60, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doesNotExceedBurst() {
    TokenBucket bucket = new TokenBucket(60, 3); // 1/sec, burst 3
    long t0 = 0L;
    for (int i = 0; i < 3; i++) {
      bucket.consumeOne(t0);
    }
    // Wait long enough for 100 tokens at 1/sec, but burst caps at 3
    long t1 = t0 + 100_000_000_000L;
    int succeeded = 0;
    for (int i = 0; i < 10; i++) {
      if (bucket.consumeOne(t1)) {
        succeeded += 1;
      }
    }
    assertThat(succeeded).isEqualTo(3);
  }
}
