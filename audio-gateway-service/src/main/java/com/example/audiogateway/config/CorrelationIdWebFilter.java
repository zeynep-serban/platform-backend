package com.example.audiogateway.config;

import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Propagate {@code X-Correlation-Id} header across the request lifecycle.
 *
 * <p>If the client provides {@code X-Correlation-Id}, normalize/validate it
 * (length-bounded) and pass through. If absent or invalid, generate a fresh UUID4.
 *
 * <p>Downstream STT services receive the id via internal headers. Logs MUST include
 * the id under {@code correlation_id} key per
 * {@code platform-k8s-gitops/docs/observability-skeleton-meeting-intelligence.md}.
 */
@Component
@Order(-100)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTR_KEY = "correlationId";
    private static final int MAX_LEN = 64;

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        final ServerHttpRequest req = exchange.getRequest();
        String corr = req.getHeaders().getFirst(HEADER);
        if (corr == null || corr.isBlank() || corr.length() > MAX_LEN) {
            corr = UUID.randomUUID().toString();
        }
        exchange.getAttributes().put(ATTR_KEY, corr);
        final ServerHttpResponse resp = exchange.getResponse();
        resp.getHeaders().set(HEADER, corr);
        return chain.filter(exchange);
    }
}
