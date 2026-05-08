package com.example.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Phase 2 PR-BE-7 (Codex iter-3 P1 #4 absorb): WebFilter behavior
 * tests for the rate limiter. Pin:
 * <ul>
 *   <li>exact path match — {@code /api/auth/cookieevil} NOT throttled</li>
 *   <li>DELETE exempt — logout always works</li>
 *   <li>token-null gate list — full burst is available, NOT halved
 *       (Codex iter-3 P1 #2 regression guard)</li>
 *   <li>burst → 429 + Retry-After</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthCookieRateLimitWebFilterTest {

  @Mock private WebFilterChain chain;

  private AuthCookieRateLimitWebFilter filter;

  void setUp(int rootBurst, int refreshBurst) {
    AuthCookieRateLimitProperties props =
        new AuthCookieRateLimitProperties(
            new AuthCookieRateLimitProperties.Refresh(60, refreshBurst),
            new AuthCookieRateLimitProperties.Root(60, rootBurst),
            new AuthCookieRateLimitProperties.Cache(1000, Duration.ofMinutes(5)));
    filter = new AuthCookieRateLimitWebFilter(props);
  }

  @Test
  void deleteExempt() {
    setUp(2, 2);
    when(chain.filter(any())).thenReturn(Mono.empty());

    // Drain the bucket via a couple of allowed requests
    MockServerWebExchange exchange1 =
        MockServerWebExchange.from(
            MockServerHttpRequest.delete("/api/auth/cookie")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 0))
                .build());
    filter.filter(exchange1, chain).block();
    assertThat(exchange1.getResponse().getStatusCode())
        .as("DELETE must always pass through")
        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void nonAuthCookiePathNotThrottled() {
    setUp(2, 2);
    when(chain.filter(any())).thenReturn(Mono.empty());

    // Codex iter-2 P0 #3: /api/auth/cookieevil must NOT match
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookieevil")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 0))
                .build());
    filter.filter(exchange, chain).block();
    assertThat(exchange.getResponse().getStatusCode())
        .as("typo path must pass through unchanged")
        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void rootBurstExhaustedReturnsRetryAfter() {
    setUp(2, 2);
    when(chain.filter(any())).thenReturn(Mono.empty());

    // Burst 2 — first 2 requests pass, 3rd is denied
    java.net.InetSocketAddress addr = new java.net.InetSocketAddress("10.0.0.2", 0);
    for (int i = 0; i < 2; i++) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/auth/cookie").remoteAddress(addr).build());
      filter.filter(exchange, chain).block();
      assertThat(exchange.getResponse().getStatusCode())
          .as("request %d should be allowed", i)
          .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    MockServerWebExchange exchange3 =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookie").remoteAddress(addr).build());
    filter.filter(exchange3, chain).block();
    assertThat(exchange3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(exchange3.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
  }

  @Test
  void noTokenDoesNotHalveBurst() {
    // Codex iter-3 P1 #2 regression guard: previous "duplicate ipBucket"
    // hack consumed the IP token TWICE per request, halving burst.
    // With the fix, full burst should be available for a no-token caller.
    setUp(4, 4);
    when(chain.filter(any())).thenReturn(Mono.empty());

    java.net.InetSocketAddress addr = new java.net.InetSocketAddress("10.0.0.3", 0);
    int allowed = 0;
    for (int i = 0; i < 4; i++) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/auth/cookie").remoteAddress(addr).build());
      filter.filter(exchange, chain).block();
      if (exchange.getResponse().getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) {
        allowed += 1;
      }
    }
    assertThat(allowed).as("full burst of 4 should be allowed for no-token caller").isEqualTo(4);
  }

  @Test
  void refreshPathUsesRefreshBucket() {
    // Refresh path is metered by refreshBurst, not rootBurst
    setUp(100, 1); // root big, refresh tight
    when(chain.filter(any())).thenReturn(Mono.empty());

    java.net.InetSocketAddress addr = new java.net.InetSocketAddress("10.0.0.4", 0);
    MockServerWebExchange first =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookie/refresh").remoteAddress(addr).build());
    filter.filter(first, chain).block();
    assertThat(first.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    MockServerWebExchange second =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookie/refresh").remoteAddress(addr).build());
    filter.filter(second, chain).block();
    assertThat(second.getResponse().getStatusCode())
        .as("refresh burst exhausted on 2nd request")
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void xffFirstIpUsedAsClientKey() {
    // Two requests with same X-Forwarded-For but different remote
    // addresses should share the same bucket (XFF wins). Burst 1
    // means first allowed, second denied.
    setUp(1, 1);
    when(chain.filter(any())).thenReturn(Mono.empty());

    MockServerWebExchange first =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookie")
                .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.99", 0))
                .build());
    filter.filter(first, chain).block();
    assertThat(first.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    MockServerWebExchange second =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/cookie")
                .header("X-Forwarded-For", "1.2.3.4, 192.168.0.1")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.42", 0))
                .build());
    filter.filter(second, chain).block();
    assertThat(second.getResponse().getStatusCode())
        .as("same XFF first IP → same bucket → 2nd denied")
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }
}
