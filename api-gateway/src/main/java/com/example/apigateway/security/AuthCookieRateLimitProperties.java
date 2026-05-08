package com.example.apigateway.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2): rate limit
 * configuration for {@code POST /api/auth/cookie} (root login
 * cookie-write) and {@code POST /api/auth/cookie/refresh} (refresh
 * closure cookie write).
 *
 * <p>Two separate buckets per (route, actor): root login is gentler
 * (more legitimate volume on cold reload), refresh is stricter
 * (a refresh storm is the abuse signal we're defending against).
 *
 * <p>Caffeine cache bounds total memory: max-size limits unique
 * (route × actor) keys; expire-after-access cleans up idle clients.
 *
 * <p>Property prefix: {@code gateway.auth-cookie.rate-limit.*}
 * (gateway-namespaced because the endpoint is a local
 * {@code @RestController} in the gateway, not auth-service).
 */
@ConfigurationProperties(prefix = "gateway.auth-cookie.rate-limit")
public record AuthCookieRateLimitProperties(
    @DefaultValue Refresh refresh,
    @DefaultValue Root root,
    @DefaultValue Cache cache) {

  public record Refresh(
      @DefaultValue("10") int perMinute,
      @DefaultValue("20") int burst) {}

  public record Root(
      @DefaultValue("30") int perMinute,
      @DefaultValue("60") int burst) {}

  public record Cache(
      @DefaultValue("10000") int maxSize,
      @DefaultValue("PT15M") Duration expireAfterAccess) {}
}
