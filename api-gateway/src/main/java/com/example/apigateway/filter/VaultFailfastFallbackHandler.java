package com.example.apigateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VaultFailfastFallbackHandler implements ErrorWebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(VaultFailfastFallbackHandler.class);
    private static final String OUTAGE_CODE = "VAULT_UNAVAILABLE";
    private static final String MESSAGE = "Kimlik altyapısı devrede değil. Bakım tamamlanınca otomatik denenecek.";

    private static final String BAD_GATEWAY_CODE = "GATEWAY_TRANSIENT";
    private static final String BAD_GATEWAY_MESSAGE = "Geçici bir ağ hatası oluştu. Birkaç saniye içinde tekrar deneyin.";

    private final ObjectMapper objectMapper;

    public VaultFailfastFallbackHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (shouldHandle(unwrapped)) {
            return writeVaultOutage(exchange, unwrapped);
        }
        // 2026-05-10 iter-2 (Codex 019e139e P1 #3 absorb):
        // Non-vault WebClientRequestException — explicit 502 Bad Gateway
        // with stable JSON shape, instead of falling through to Spring's
        // default 500 Internal Server Error. The original PR #152 iter-1
        // claimed "502 Bad Gateway" in the description but the code only
        // delegated; that gap broke the contract that frontend recovery
        // logic relies on. By writing 502 explicitly we own the
        // transient-vs-outage distinction at the gateway layer rather
        // than leaking 500s with no taxonomy hint.
        if (unwrapped instanceof WebClientRequestException) {
            return writeBadGateway(exchange, unwrapped);
        }
        return Mono.error(ex);
    }

    private Mono<Void> writeVaultOutage(ServerWebExchange exchange, Throwable cause) {
        log.warn("Vault fail-fast fallback devreye alındı: {}", cause.getMessage());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Duration.ofMinutes(1).getSeconds()));
        response.getHeaders().set("X-Serban-Outage-Code", OUTAGE_CODE);

        Map<String, Object> payload = buildPayload(exchange);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private Mono<Void> writeBadGateway(ServerWebExchange exchange, Throwable cause) {
        log.warn("Gateway transient: {} — returning 502 (retriable)", cause.getMessage());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_GATEWAY);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Short Retry-After: this is a transient, retry quickly
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
        // Distinct outage code so client/observability can differentiate
        // transient vs vault outage
        response.getHeaders().set("X-Serban-Outage-Code", BAD_GATEWAY_CODE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_gateway");
        body.put("message", BAD_GATEWAY_MESSAGE);
        body.put("fieldErrors", Collections.emptyList());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", resolveTraceId(exchange));
        meta.put("outageCode", BAD_GATEWAY_CODE);
        body.put("meta", meta);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private boolean shouldHandle(Throwable throwable) {
        if (throwable instanceof NotFoundException) {
            return true;
        }
        if (throwable instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            return status != null && (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT);
        }
        // 2026-05-10 hot-fix (login flow):
        // Only fire on GENUINE connection-level failures, not on every
        // WebClientRequestException. The previous broad catch:
        //   {@code if (throwable instanceof WebClientRequestException) return true;}
        // converted any transient WebClient failure (DNS retry, brief
        // connection drop, slow upstream) into a 503 with a misleading
        // "Kimlik altyapısı devrede değil" page, breaking user-facing
        // gateway-routed endpoints (e.g. {@code /api/auth/cookie} POST
        // observed in earlier session smoke) even when the actual
        // root cause was a healthy-cluster HTTP-level transient.
        //
        // Scope: this fixes /api/* gateway-routed errors. KC routes
        // ({@code /realms/**}) bypass the gateway entirely (host
        // nginx proxy_pass to KC pod directly) — KC pod 503s require
        // a separate KC pod / host nginx investigation.
        //
        // {@code Exceptions.unwrap()} only unwraps reactor-specific
        // wrappers (CompositeException, ReactiveException); it does
        // NOT follow {@code getCause()}. WebClientRequestException
        // typically wraps a ConnectException via getCause(), so we
        // must walk the cause chain explicitly to find the
        // connection-level root.
        Throwable root = Exceptions.unwrap(throwable);
        return hasConnectionLevelCause(root);
    }

    /**
     * Walk the {@code getCause()} chain looking for a genuine
     * connection-level failure: ConnectException, NoRouteToHostException,
     * SocketTimeoutException, or TimeoutException. Bounded depth (8)
     * to avoid infinite loops on circular cause chains.
     */
    private static boolean hasConnectionLevelCause(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur instanceof ConnectException
                    || cur instanceof NoRouteToHostException
                    || cur instanceof SocketTimeoutException
                    || cur instanceof TimeoutException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private Map<String, Object> buildPayload(ServerWebExchange exchange) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "vault_unavailable");
        body.put("message", MESSAGE);
        body.put("fieldErrors", Collections.emptyList());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", resolveTraceId(exchange));
        meta.put("outageCode", OUTAGE_CODE);
        body.put("meta", meta);
        return body;
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        header = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return exchange.getRequest().getId();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
