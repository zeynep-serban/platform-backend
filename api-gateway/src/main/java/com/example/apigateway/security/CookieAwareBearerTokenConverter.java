package com.example.apigateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authentication converter that accepts a Bearer token via either the
 * standard {@code Authorization} header or — for the SSE inbox stream
 * endpoint only — the {@code erp_access_token} HttpOnly cookie.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Browsers cannot attach an {@code Authorization} header to native
 * {@code EventSource} connections (DOM standard limitation). The
 * notification-orchestrator inbox SSE endpoint is otherwise reachable
 * by the SPA via the same JWT cookie that drives every other API
 * call ({@code erp_access_token}, set HttpOnly + Secure +
 * SameSite=Lax by {@link com.example.apigateway.filter.AuthCookieEndpoint}),
 * but Spring Security's default {@link ServerBearerTokenAuthenticationConverter}
 * only looks at the header and rejects the request with 401 before
 * any downstream filter (including the existing
 * {@link com.example.apigateway.filter.CookieToAuthHeaderFilter}
 * GlobalFilter) gets to copy the cookie value into the header.
 *
 * <h3>Decision rationale</h3>
 *
 * <p>Codex thread {@code 019e0494} AGREE iter-1: SSE auth fix lives at
 * the gateway-security layer, not at the FE (no
 * {@code @microsoft/fetch-event-source} rewrite) and not at the
 * backend (notification-orchestrator's servlet stack
 * {@link org.springframework.security.config.annotation.web.builders.HttpSecurity}
 * keeps its bearer-only contract; subscriber-identity claim match in
 * {@code SubscriberIdentityGuard} stays the inner authorization
 * boundary). The cookie fallback also rejects the
 * <strong>query-string token</strong> shape (option B in the plan)
 * because tokens leak into browser history, gateway access logs,
 * and downstream observability — the HttpOnly cookie has none of
 * those problems.
 *
 * <h3>Scope of the cookie fallback</h3>
 *
 * <p>The fallback fires <em>only</em> when ALL of the following are
 * true:
 * <ul>
 *   <li>HTTP method is {@code GET}</li>
 *   <li>Path equals exactly {@code /api/v1/notify/inbox/me/stream}</li>
 *   <li>Request carries no {@code Authorization} header</li>
 *   <li>Request carries a non-blank {@code erp_access_token} cookie</li>
 * </ul>
 *
 * <p>Every other path keeps the standard
 * {@link ServerBearerTokenAuthenticationConverter} contract — header
 * only, no cookie magic. This narrow scope keeps the SSE-specific
 * relaxation auditable and prevents the cookie path from quietly
 * authenticating arbitrary endpoints.
 *
 * <h3>Token validation</h3>
 *
 * <p>This converter only adapts the <em>source</em> of the token; it
 * does not validate the token. The downstream
 * {@code ReactiveJwtDecoder} (already wired in
 * {@link SecurityConfig#jwtDecoder()}) performs signature, expiry,
 * audience, and issuer-allowlist checks exactly as it does for
 * header-source tokens. A junk cookie value produces the same 401
 * response the header path produces.
 */
@Component
public class CookieAwareBearerTokenConverter implements ServerAuthenticationConverter {

    /**
     * Cookie name set by {@code AuthCookieEndpoint} on successful
     * password / refresh exchange. HttpOnly + Secure + SameSite=Lax.
     */
    static final String COOKIE_NAME = "erp_access_token";

    /**
     * Exact path that opts in to the cookie fallback. Anchored on the
     * full notification-orchestrator SSE route. The trailing
     * {@code /me/stream} is meaningful — neither the bulk
     * {@code /me/mark-all-read} nor the listing {@code /me} variant
     * is allowed to use the cookie source.
     */
    static final String SSE_PATH = "/api/v1/notify/inbox/me/stream";

    private final ServerBearerTokenAuthenticationConverter delegate =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<org.springframework.security.core.Authentication> convert(ServerWebExchange exchange) {
        Mono<org.springframework.security.core.Authentication> headerSource = delegate.convert(exchange);
        return headerSource.switchIfEmpty(Mono.defer(() -> cookieFallback(exchange)));
    }

    /**
     * Returns a {@link BearerTokenAuthenticationToken} sourced from
     * the {@code erp_access_token} cookie when the request matches
     * the SSE-only fallback contract; otherwise empty (the security
     * filter chain then proceeds to the normal "no credentials → 401"
     * path).
     */
    private Mono<AbstractAuthenticationToken> cookieFallback(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        if (!HttpMethod.GET.equals(request.getMethod())) {
            return Mono.empty();
        }

        String path = request.getPath().value();
        if (path == null || !path.equals(SSE_PATH)) {
            return Mono.empty();
        }

        if (request.getHeaders().containsKey("Authorization")) {
            // Defensive — delegate.convert(...) would already have
            // handled this; the empty-Mono above means the header was
            // absent, but make the contract explicit so a future code
            // path that pre-strips the header still hits the right
            // branch.
            return Mono.empty();
        }

        HttpCookie cookie = request.getCookies().getFirst(COOKIE_NAME);
        if (cookie == null) {
            return Mono.empty();
        }

        String tokenValue = cookie.getValue();
        if (tokenValue == null || tokenValue.isBlank()) {
            return Mono.empty();
        }

        return Mono.just(new BearerTokenAuthenticationToken(tokenValue));
    }
}
