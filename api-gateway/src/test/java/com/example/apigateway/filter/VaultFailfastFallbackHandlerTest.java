package com.example.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class VaultFailfastFallbackHandlerTest {

    private final VaultFailfastFallbackHandler handler = new VaultFailfastFallbackHandler(new ObjectMapper());

    @Test
    void handlesServiceUnavailableByReturningMaintenancePayload() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
                        .header("X-Trace-Id", "trace-123"));

        handler.handle(exchange, new NotFoundException("Unable to find instance for user-service")).block();

        ServerHttpResponse response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Serban-Outage-Code")).isEqualTo("VAULT_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");

        String body = ((MockServerHttpResponse) response).getBodyAsString().block();
        assertThat(body).contains("\"error\":\"vault_unavailable\"");
        assertThat(body).contains("\"outageCode\":\"VAULT_UNAVAILABLE\"");
        assertThat(body).contains("\"traceId\":\"trace-123\"");
    }

    @Test
    void delegatesWhenExceptionIsNotVaultRelated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
        );

        StepVerifier.create(handler.handle(exchange, new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    /**
     * 2026-05-10 regression guard (login flow hot-fix):
     * Before this fix, ANY {@link org.springframework.web.reactive.function.client.WebClientRequestException}
     * unconditionally fired the 503 + VAULT_UNAVAILABLE response — even
     * a transient HTTP-level error with no underlying connect failure.
     * Live cluster smoke 2026-05-10:
     *   /realms/platform-test/protocol/openid-connect/auth → 503 (first attempt)
     *   /realms/platform-test/protocol/openid-connect/auth → 200 (retry)
     * Classic transient hiccup that should NOT have surfaced as a vault
     * outage page; the misleading 503 broke the user's login flow.
     *
     * <p>The fix narrows the catch to genuine connection-level failures
     * via the unwrapped root cause — a WebClientRequestException whose
     * underlying cause is something else (e.g. a generic transport
     * issue, a malformed response, a slow connection that didn't reach
     * actual timeout) now falls through to Spring's default 502 Bad
     * Gateway error handler so the client sees a retriable error
     * instead of a "vault down" outage page.
     */
    @Test
    void doesNotHandleWebClientRequestExceptionWithoutConnectionRootCause() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
        );

        // WebClientRequestException whose underlying cause is just a
        // generic RuntimeException (NOT ConnectException / SocketTimeout
        // / NoRouteToHost / TimeoutException) — represents an HTTP-level
        // transient that the loadbalancer retry should handle, not a
        // vault outage. With the iter-1 fix this falls through (handler
        // returns Mono.error(ex) so Spring's default error handler runs).
        org.springframework.web.reactive.function.client.WebClientRequestException ex =
                new org.springframework.web.reactive.function.client.WebClientRequestException(
                        new RuntimeException("transient WebClient HTTP error"),
                        org.springframework.http.HttpMethod.GET,
                        java.net.URI.create("http://upstream/realms/platform-test/protocol/openid-connect/auth"),
                        new org.springframework.http.HttpHeaders());

        StepVerifier.create(handler.handle(exchange, ex))
                .expectErrorMatches(thrown ->
                        thrown instanceof org.springframework.web.reactive.function.client.WebClientRequestException)
                .verify();
    }

    @Test
    void stillHandlesWebClientRequestExceptionWithConnectExceptionRootCause() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
                        .header("X-Trace-Id", "trace-456"));

        // Genuine connect-level failure → SHOULD still trigger the 503 +
        // VAULT_UNAVAILABLE response (this is what the handler is for:
        // KC/Vault genuinely unreachable).
        org.springframework.web.reactive.function.client.WebClientRequestException ex =
                new org.springframework.web.reactive.function.client.WebClientRequestException(
                        new java.net.ConnectException("Connection refused: keycloak/172.19.0.5:8080"),
                        org.springframework.http.HttpMethod.GET,
                        java.net.URI.create("http://keycloak:8080/realms/platform-test"),
                        new org.springframework.http.HttpHeaders());

        handler.handle(exchange, ex).block();

        ServerHttpResponse response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Serban-Outage-Code")).isEqualTo("VAULT_UNAVAILABLE");
    }
}
