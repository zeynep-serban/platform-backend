package com.example.apigateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2): rate limiter for
 * {@code POST /api/auth/cookie} (root login cookie-write) and
 * {@code POST /api/auth/cookie/refresh} (refresh closure cookie
 * write). Soft throttle / storm dampener — cluster-wide hard limit
 * is NOT guaranteed (HPA replicas multiply the effective threshold).
 *
 * <p>Why a {@code WebFilter} (not Spring Cloud Gateway
 * {@code RequestRateLimiter} route config): the {@code /api/auth/cookie}
 * endpoint is a local {@code @RestController} in the gateway
 * ({@code AuthCookieEndpoint}), not a route to auth-service.
 * Adding a SCG route for rate limiting would create a proxy loop or
 * miss the controller entirely (Codex iter-0 P0 #2 absorb).
 *
 * <p>Filter order: above Spring Security so 429s short-circuit
 * before security filter chain runs (security filter expects a
 * happy-path request; 429 is a transport-level throttle).
 *
 * <p>Key strategy (Codex iter-2 P0 #1+#2 absorb):
 * <ul>
 *   <li>Per-route bucket: {@code auth_cookie} and
 *       {@code auth_cookie_refresh} have separate buckets even for
 *       the same actor.</li>
 *   <li>Dual bucket: one bucket keyed by IP (always), one keyed by
 *       bearer token fingerprint (when token present). Both must
 *       have a token; atomicity guaranteed by
 *       {@link MultiBucketGate#tryAcquireAll}.</li>
 * </ul>
 *
 * <p>{@code DELETE /api/auth/cookie} is exempt: logout must always
 * work (Codex iter-0 P2 #2 absorb). Locking out logout would create
 * an incident-recovery hole.
 *
 * <p>Trusted proxy: the gateway sits behind ingress-nginx with
 * {@code use-forwarded-headers} on. {@code X-Forwarded-For} is
 * trusted (operator enforces ingress IP whitelist). Spoof risk for
 * a soft throttle is acceptable; acknowledged in the PR description.
 */
@Component
public class AuthCookieRateLimitWebFilter implements WebFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(AuthCookieRateLimitWebFilter.class);

  private static final String PATH_ROOT = "/api/auth/cookie";
  private static final String PATH_REFRESH = "/api/auth/cookie/refresh";
  private static final String GROUP_ROOT = "auth_cookie";
  private static final String GROUP_REFRESH = "auth_cookie_refresh";

  private final AuthCookieRateLimitProperties properties;
  private final Cache<String, TokenBucket> bucketCache;

  public AuthCookieRateLimitWebFilter(AuthCookieRateLimitProperties properties) {
    this.properties = properties;
    this.bucketCache =
        Caffeine.newBuilder()
            .maximumSize(properties.cache().maxSize())
            .expireAfterAccess(properties.cache().expireAfterAccess())
            .build();
  }

  /**
   * Order: HIGHEST_PRECEDENCE + 50 — between CORS (HIGHEST) and the
   * telemetry web filter (HIGHEST + 100). Rate limit before security
   * runs so a 429 is returned without consuming security stack
   * resources.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 50;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    HttpMethod method = exchange.getRequest().getMethod();

    // Exact path match (Codex iter-2 P0 #3 absorb): startsWith would
    // catch "/api/auth/cookieevil" — equals() is the safe guard.
    boolean isRoot = PATH_ROOT.equals(path);
    boolean isRefresh = PATH_REFRESH.equals(path);
    if (!HttpMethod.POST.equals(method) || (!isRoot && !isRefresh)) {
      return chain.filter(exchange);
    }

    String routeGroup = isRefresh ? GROUP_REFRESH : GROUP_ROOT;
    AuthCookieRateLimitProperties.Refresh refresh = properties.refresh();
    AuthCookieRateLimitProperties.Root root = properties.root();
    int perMinute = isRefresh ? refresh.perMinute() : root.perMinute();
    int burst = isRefresh ? refresh.burst() : root.burst();

    String clientIp = resolveClientIp(exchange);
    String tokenFingerprint = resolveTokenFingerprint(exchange);

    String ipBucketKey = routeGroup + ":ip:" + clientIp;
    TokenBucket ipBucket = bucketCache.get(ipBucketKey, k -> new TokenBucket(perMinute, burst));

    TokenBucket tokenBucket = null;
    if (tokenFingerprint != null) {
      String tokenBucketKey = routeGroup + ":token:" + tokenFingerprint;
      tokenBucket = bucketCache.get(tokenBucketKey, k -> new TokenBucket(perMinute, burst));
    }

    long nowNanos = System.nanoTime();
    // Codex iter-3 P1 #2 absorb: when there's no token fingerprint,
    // pass ONLY the IP bucket. The previous "duplicate ipBucket"
    // hack consumed the IP token TWICE per request, halving the
    // effective burst for unauthenticated callers.
    List<TokenBucket> buckets =
        tokenBucket == null
            ? java.util.Collections.singletonList(ipBucket)
            : List.of(ipBucket, tokenBucket);
    MultiBucketGate.Decision decision = MultiBucketGate.tryAcquireAll(buckets, nowNanos);

    if (!decision.allowed()) {
      // Log with masked actor key — full IP/fingerprint never logged
      log.warn(
          "auth-cookie rate limit denied: route={} actor_prefix={} retry_after={}s",
          routeGroup,
          maskActor(clientIp, tokenFingerprint),
          decision.retryAfterSeconds());
      exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
      exchange
          .getResponse()
          .getHeaders()
          .add(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
      return exchange.getResponse().setComplete();
    }

    return chain.filter(exchange);
  }

  /**
   * Resolve the client IP. Trusts {@code X-Forwarded-For} first IP
   * (ingress-nginx is the trusted proxy); falls back to remote
   * address. {@code unknown} when neither is available.
   */
  private String resolveClientIp(ServerWebExchange exchange) {
    HttpHeaders headers = exchange.getRequest().getHeaders();
    String xff = headers.getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      // First IP in comma-separated list is the original client
      return xff.split(",")[0].trim();
    }
    InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
    if (addr != null && addr.getAddress() != null) {
      return addr.getAddress().getHostAddress();
    }
    return "unknown";
  }

  /**
   * SHA-256 prefix (first 16 hex chars / 64 bits) of the bearer
   * token. Returns {@code null} if no Authorization header — token
   * bucket is then optional (Codex iter-2 P0 #2 absorb).
   *
   * <p>Raw token NEVER stored or logged. The prefix is enough to
   * differentiate unique tokens for rate-limit bucketing without
   * being reversible.
   */
  private String resolveTokenFingerprint(ServerWebExchange exchange) {
    String authz = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authz == null || !authz.startsWith("Bearer ")) {
      return null;
    }
    String token = authz.substring("Bearer ".length()).trim();
    if (token.length() < 16) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(hash);
      return hex.substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every JRE; this branch is dead but
      // declared for compile-time safety.
      log.warn("SHA-256 not available, skipping token fingerprint", e);
      return null;
    }
  }

  /**
   * Mask the full actor key for logging. Keeps the route prefix and
   * a 6-character actor identifier, then a sentinel.
   */
  private String maskActor(String clientIp, String tokenFingerprint) {
    String ip = clientIp == null ? "unknown" : clientIp;
    String ipMasked = ip.length() <= 6 ? ip + "***" : ip.substring(0, 6) + "***";
    if (tokenFingerprint == null) {
      return "ip=" + ipMasked;
    }
    String tokenMasked = tokenFingerprint.length() <= 6
        ? tokenFingerprint + "***"
        : tokenFingerprint.substring(0, 6) + "***";
    return "ip=" + ipMasked + " token=" + tokenMasked;
  }

  @Configuration
  @EnableConfigurationProperties(AuthCookieRateLimitProperties.class)
  static class Config {}
}
