package com.example.apigateway.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Phase 2 PR-BE-7 (Codex iter-3 P1 #4 absorb): WebFilter behavior
 * tests for the auth telemetry meter. Pin:
 * <ul>
 *   <li>cookie name detection ({@code erp_access_token} not
 *       {@code auth_token} — Codex iter-3 P0 #1 regression guard)</li>
 *   <li>bearer-only path</li>
 *   <li>none path (no cookie, no Authorization)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GatewayAuthTelemetryWebFilterTest {

  @Mock private WebFilterChain chain;

  private MeterRegistry registry;
  private GatewayRouteClassifier classifier;
  private GatewayAuthTelemetryWebFilter filter;

  void setUp() {
    registry = new SimpleMeterRegistry();
    classifier = new GatewayRouteClassifier();
    filter = new GatewayAuthTelemetryWebFilter(registry, classifier);
  }

  @Test
  void unauthorizedWithCookie_recordsCookieAuthMode() {
    setUp();
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/v1/users/123")
            .cookie(new org.springframework.http.HttpCookie("erp_access_token", "value"))
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    when(chain.filter(any())).thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    Counter c =
        registry
            .find(GatewayAuthTelemetryWebFilter.METER_UNAUTHORIZED)
            .tag("route_group", "users")
            .tag("auth_mode", "cookie")
            .counter();
    assertThat(c).isNotNull();
    assertThat(c.count()).isEqualTo(1.0);
  }

  @Test
  void unauthorizedWithBearer_recordsBearerAuthMode() {
    setUp();
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/v1/users/123")
            .header("Authorization", "Bearer xyz")
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    when(chain.filter(any())).thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    Counter c =
        registry
            .find(GatewayAuthTelemetryWebFilter.METER_UNAUTHORIZED)
            .tag("route_group", "users")
            .tag("auth_mode", "bearer")
            .counter();
    assertThat(c).isNotNull();
    assertThat(c.count()).isEqualTo(1.0);
  }

  @Test
  void unauthorizedNoAuth_recordsNoneAuthMode() {
    setUp();
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    when(chain.filter(any())).thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    Counter c =
        registry
            .find(GatewayAuthTelemetryWebFilter.METER_UNAUTHORIZED)
            .tag("route_group", "users")
            .tag("auth_mode", "none")
            .counter();
    assertThat(c).isNotNull();
    assertThat(c.count()).isEqualTo(1.0);
  }

  @Test
  void successResponseDoesNotIncrementCounters() {
    setUp();
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    exchange.getResponse().setStatusCode(HttpStatus.OK);
    when(chain.filter(any())).thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    assertThat(registry.find(GatewayAuthTelemetryWebFilter.METER_UNAUTHORIZED).counters())
        .isEmpty();
    assertThat(registry.find(GatewayAuthTelemetryWebFilter.METER_FORBIDDEN).counters()).isEmpty();
  }

  @Test
  void forbiddenIncrementsForbiddenCounter() {
    setUp();
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/v1/reports").header("Authorization", "Bearer x").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
    when(chain.filter(any())).thenReturn(Mono.empty());

    filter.filter(exchange, chain).block();

    Counter c =
        registry
            .find(GatewayAuthTelemetryWebFilter.METER_FORBIDDEN)
            .tag("route_group", "reports")
            .tag("auth_mode", "bearer")
            .counter();
    assertThat(c).isNotNull();
    assertThat(c.count()).isEqualTo(1.0);
  }
}
