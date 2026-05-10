package com.example.apigateway.filter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cookie management endpoints for httpOnly token storage.
 *
 * Frontend flow:
 *   1. Login via Keycloak → get access_token
 *   2. POST /api/auth/cookie with Bearer token
 *   3. Gateway sets httpOnly cookie
 *   4. Subsequent requests use cookie (no localStorage)
 *
 * Logout flow:
 *   1. DELETE /api/auth/cookie
 *   2. Cookie cleared
 */
@RestController
@RequestMapping("/api/auth/cookie")
public class AuthCookieEndpoint {

    private static final String COOKIE_NAME = "erp_access_token";

    /**
     * Set the access token as an httpOnly cookie.
     * Frontend calls this after Keycloak login (POST /api/auth/cookie) and on
     * silent token refresh (POST /api/auth/cookie/refresh).
     *
     * The /refresh suffix mirrors the frontend AuthBootstrapper contract — root
     * POST is the initial cookie write, /refresh is the periodic re-write each
     * time the Keycloak adapter rotates the access_token. Both paths share the
     * same logic (write Bearer token to httpOnly cookie); separating them lets
     * observability/audit pipelines distinguish login vs. refresh hits.
     */
    @PostMapping(path = {"", "/refresh"})
    public Mono<ResponseEntity<Void>> setTokenCookie(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.badRequest().build());
        }

        String token = authHeader.substring(7);
        boolean isSecure = isSecureContext(exchange);

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .sameSite(isSecure ? "Strict" : "Lax")
                .maxAge(3600) // 1 hour, should match token TTL
                .build();

        // Live login bug fix (testai 2026-05-10): exchange.getResponse().addCookie()
        // is dropped when the controller returns a freshly-built ResponseEntity —
        // the new response builder does not propagate cookies set on the exchange.
        // Attach Set-Cookie explicitly to the ResponseEntity so the header reaches
        // the browser regardless of WebFlux response-commit timing.
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .<Void>build());
    }

    /**
     * Clear the access token cookie (logout).
     */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> clearTokenCookie(ServerWebExchange exchange) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0) // Expire immediately
                .build();

        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .<Void>build());
    }

    private boolean isSecureContext(ServerWebExchange exchange) {
        String scheme = exchange.getRequest().getURI().getScheme();
        return "https".equalsIgnoreCase(scheme);
    }
}
