package com.example.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Unit tests for {@link AuthCookieEndpoint}.
 *
 * <p>Hot-fix 2026-05-10 (testai live login broken): {@code POST /api/auth/cookie}
 * returned HTTP 200 but the {@code Set-Cookie} response header was missing —
 * {@code exchange.getResponse().addCookie()} is dropped when the controller
 * returns a freshly-built {@link org.springframework.http.ResponseEntity}. The
 * fix attaches {@code Set-Cookie} directly to the {@code ResponseEntity}; these
 * tests pin that contract so the regression cannot recur.
 *
 * <p>Bound directly to the controller via {@link WebTestClient#bindToController}
 * to keep the assertion narrow (no Spring Security context, no rate limiter,
 * no Gateway routing — just the controller's response shape). The
 * bind-to-controller mock setup does not populate the typed cookie multimap,
 * so we assert directly on the raw {@code Set-Cookie} header — which is also
 * the precise surface the live regression broke.
 */
class AuthCookieEndpointTest {

    private static final String COOKIE_NAME = "erp_access_token";

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new AuthCookieEndpoint())
                .configureClient()
                .baseUrl("http://localhost")
                .build();
    }

    private static String setCookieHeader(EntityExchangeResult<?> result) {
        List<String> values = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(values)
                .as("Set-Cookie header must be present (live login regression guard)")
                .isNotNull()
                .isNotEmpty();
        return values.get(0);
    }

    @Test
    void postSetsCookieWithSetCookieHeader() {
        EntityExchangeResult<Void> result = client.post()
                .uri("/api/auth/cookie")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-value")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(Void.class)
                .returnResult();

        String setCookie = setCookieHeader(result);
        assertThat(setCookie).contains(COOKIE_NAME + "=test-token-value");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("Max-Age=3600");
        assertThat(setCookie).contains("HttpOnly");
        // http:// scheme → secure=false, sameSite=Lax
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void postWithoutBearerReturnsBadRequestAndNoCookie() {
        client.post()
                .uri("/api/auth/cookie")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void postWithMalformedAuthHeaderReturnsBadRequest() {
        client.post()
                .uri("/api/auth/cookie")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void postRefreshSetsCookieToo() {
        EntityExchangeResult<Void> result = client.post()
                .uri("/api/auth/cookie/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer refresh-token-test")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(Void.class)
                .returnResult();

        String setCookie = setCookieHeader(result);
        assertThat(setCookie).contains(COOKIE_NAME + "=refresh-token-test");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Path=/");
    }

    @Test
    void deleteClearsCookie() {
        EntityExchangeResult<Void> result = client.delete()
                .uri("/api/auth/cookie")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(Void.class)
                .returnResult();

        String setCookie = setCookieHeader(result);
        assertThat(setCookie).contains(COOKIE_NAME + "=");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Path=/");
    }

    /**
     * Regression guard: the controller must emit exactly one Set-Cookie header.
     *
     * <p>Before the hot-fix, the controller called {@code exchange.getResponse().addCookie()}
     * AND returned {@code ResponseEntity.ok().build()}. The fix removed the
     * {@code exchange.getResponse().addCookie()} call and switched to
     * {@code ResponseEntity.header(SET_COOKIE, ...)} so the response carries the
     * cookie via the canonical {@code ResponseEntity}-attached header path. If a
     * future change re-introduces a duplicate {@code addCookie()} call, this test
     * fails — protecting both the original drop bug AND the over-correction
     * (double Set-Cookie causing some clients to use only the first value).
     */
    @Test
    void postEmitsExactlyOneSetCookieHeader() {
        EntityExchangeResult<Void> result = client.post()
                .uri("/api/auth/cookie")
                .header(HttpHeaders.AUTHORIZATION, "Bearer single-header-test")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Void.class)
                .returnResult();

        List<String> setCookies = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies)
                .as("Set-Cookie header must be present and emitted exactly once")
                .isNotNull()
                .hasSize(1);
        assertThat(setCookies.get(0)).contains(COOKIE_NAME + "=single-header-test");
    }
}
