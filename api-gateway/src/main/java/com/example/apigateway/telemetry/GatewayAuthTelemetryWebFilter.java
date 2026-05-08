package com.example.apigateway.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2 absorb): WebFilter
 * that records {@code 401} and {@code 403} response counts as
 * Micrometer counters with bounded {@code route_group + auth_mode}
 * tags.
 *
 * <p>Why a {@code WebFilter} (not a Spring Cloud Gateway
 * {@code GlobalFilter}): Spring Security's
 * {@code authenticationEntryPoint} writes {@code 401} BEFORE SCG
 * GlobalFilters run, so a GlobalFilter would miss those statuses
 * entirely. WebFilter wraps the entire WebFlux chain including
 * security; {@code doFinally} reliably observes the final response
 * status across success and error completion paths (Codex iter-0
 * P0 #1 absorb).
 *
 * <p>Cardinality contract:
 * <ul>
 *   <li>{@code route_group}: bounded enum from
 *       {@link GatewayRouteClassifier} ({@code users | reports |
 *       schemas | notify | auth_cookie | auth_cookie_refresh |
 *       authz_me | auth_sessions | theme_registry | audit | access |
 *       variants | admin | auth_meta | unknown})</li>
 *   <li>{@code auth_mode}: {@code cookie | bearer | none}</li>
 * </ul>
 *
 * <p>URL, header, body, token MUST NOT be a tag (cardinality + PII
 * protection — same contract as the frontend
 * {@code @mfe/shared-http/observability} module from PR-Obs-5).
 */
@Component
public class GatewayAuthTelemetryWebFilter implements WebFilter, Ordered {

  static final String METER_UNAUTHORIZED = "gateway.auth.unauthorized";
  static final String METER_FORBIDDEN = "gateway.auth.forbidden";

  static final String AUTH_MODE_COOKIE = "cookie";
  static final String AUTH_MODE_BEARER = "bearer";
  static final String AUTH_MODE_NONE = "none";

  private final MeterRegistry registry;
  private final GatewayRouteClassifier classifier;

  public GatewayAuthTelemetryWebFilter(MeterRegistry registry, GatewayRouteClassifier classifier) {
    this.registry = registry;
    this.classifier = classifier;
  }

  /**
   * Order: high precedence so the filter wraps Spring Security and
   * observes the final response status. Below the rate limiter
   * (which short-circuits with 429 before security runs) so we count
   * 401/403 from security, not 429s from rate limiting.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 100;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    final String routeGroup = classifier.classify(exchange.getRequest().getURI().getPath());
    final String authMode = detectAuthMode(exchange);
    return chain
        .filter(exchange)
        .doFinally(signal -> {
          // doFinally fires for both success and error completion.
          // Read the final status code; null happens on transport
          // errors before any handler set it — skip those.
          if (exchange.getResponse().getStatusCode() == null) {
            return;
          }
          int status = exchange.getResponse().getStatusCode().value();
          if (status == 401) {
            registry
                .counter(METER_UNAUTHORIZED, "route_group", routeGroup, "auth_mode", authMode)
                .increment();
          } else if (status == 403) {
            registry
                .counter(METER_FORBIDDEN, "route_group", routeGroup, "auth_mode", authMode)
                .increment();
          }
        });
  }

  /**
   * Detect the auth mode from the inbound request. Order matters:
   * cookie wins over bearer because the frontend always uses cookies
   * post-PR-Auth-1 (httpOnly set by /auth/cookie); if both are
   * present, the cookie was the actual auth signal that reached the
   * gateway.
   *
   * <p>Codex iter-3 P0 #1 absorb: the cookie name is
   * {@code erp_access_token}, NOT {@code auth_token}. See
   * {@code AuthCookieEndpoint}, {@code CookieToAuthHeaderFilter},
   * and {@code CookieAwareBearerTokenConverter} for the canonical
   * cookie-name source. Reading via {@code request.getCookies()} is
   * safer than parsing the raw {@code Cookie} header string.
   */
  private String detectAuthMode(ServerWebExchange exchange) {
    if (exchange.getRequest().getCookies().containsKey(AUTH_COOKIE_NAME)) {
      return AUTH_MODE_COOKIE;
    }
    HttpHeaders headers = exchange.getRequest().getHeaders();
    String authz = headers.getFirst(HttpHeaders.AUTHORIZATION);
    if (authz != null && !authz.isBlank()) {
      return AUTH_MODE_BEARER;
    }
    return AUTH_MODE_NONE;
  }

  /**
   * Canonical gateway auth cookie name. Mirrors
   * {@code AuthCookieEndpoint.AUTH_COOKIE_NAME}; declared local-static
   * here so this class doesn't depend on the controller package.
   */
  static final String AUTH_COOKIE_NAME = "erp_access_token";
}
