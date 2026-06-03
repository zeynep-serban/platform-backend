package com.example.audiogateway.config;

import com.example.audiogateway.dto.ErrorResponse;

import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Canonical {@link ErrorResponse} envelope for body / decode / validation failures.
 *
 * <p>Codex {@code 019e8c26} iter-3 P1 absorb: {@code @Valid} kaynaklı
 * {@link WebExchangeBindException} ve JSON decode error'ları Spring default body'sine
 * düşmemeli; {@link ErrorResponse} envelope ile dönmeli (correlationId + code + message
 * + retryable + PII'sız details).
 *
 * <p>Error code mapping:
 * <ul>
 *   <li>Missing/blank {@code language} → {@code AUDIO_GATEWAY_LANGUAGE_REQUIRED}</li>
 *   <li>Diğer bean validation → {@code AUDIO_GATEWAY_VALIDATION}</li>
 *   <li>JSON decode / parse / enum mismatch → {@code AUDIO_GATEWAY_VALIDATION} (400)</li>
 * </ul>
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> onBind(final WebExchangeBindException ex,
                                                      final ServerWebExchange exchange) {
        final String corrId = correlationId(exchange);
        final FieldError languageErr = ex.getBindingResult().getFieldErrors().stream()
                .filter(fe -> "language".equals(fe.getField()))
                .findFirst()
                .orElse(null);
        if (languageErr != null) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_LANGUAGE_REQUIRED,
                    "language required (ISO 639-1, e.g. tr / en / de; optional region tr-TR)",
                    corrId, false)));
        }
        final FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        final String msg = first != null
                ? "Validation failed for field '" + first.getField() + "': " + first.getDefaultMessage()
                : "Validation failed";
        return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                ErrorResponse.CODE_VALIDATION,
                msg, corrId, false)));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ErrorResponse>> onInput(final ServerWebInputException ex,
                                                       final ServerWebExchange exchange) {
        final String corrId = correlationId(exchange);
        final Throwable cause = ex.getMostSpecificCause();
        // DecodingException → malformed JSON / unknown enum
        final boolean isDecode = cause instanceof DecodingException
                || (cause != null && cause.getClass().getName().contains("InvalidFormatException"));
        final String code = isDecode ? ErrorResponse.CODE_VALIDATION : ErrorResponse.CODE_VALIDATION;
        final String msg = ex.getReason() != null ? ex.getReason() : "Request body decode failed";
        return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                code, msg, corrId, false)));
    }

    private static String correlationId(final ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(CorrelationIdWebFilter.ATTR_KEY);
    }
}
