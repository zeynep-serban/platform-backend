package com.example.audiogateway.dto;

import java.util.Map;

/**
 * Canonical error envelope — Codex {@code 019e879c} contract.
 *
 * <p>{@code code} machine-parseable; {@code message} human-friendly; {@code correlationId}
 * propagated; {@code retryable} backpressure hint. {@code details} optional but MUST NOT
 * contain PII (audio path, transcript text, full filename, user email raw).
 */
public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        boolean retryable,
        Map<String, Object> details
) {

    public static final String CODE_AUTH_INVALID = "AUDIO_GATEWAY_AUTH_INVALID";
    public static final String CODE_LANGUAGE_REQUIRED = "AUDIO_GATEWAY_LANGUAGE_REQUIRED";
    public static final String CODE_VALIDATION = "AUDIO_GATEWAY_VALIDATION";
    public static final String CODE_FORMAT_REJECTED = "AUDIO_GATEWAY_FORMAT_REJECTED";
    public static final String CODE_OVERSIZE = "AUDIO_GATEWAY_OVERSIZE";
    public static final String CODE_QUEUE_FULL = "AUDIO_GATEWAY_QUEUE_FULL";
    public static final String CODE_STT_UNAVAILABLE = "AUDIO_GATEWAY_STT_UNAVAILABLE";
    public static final String CODE_MEETING_FORBIDDEN = "AUDIO_GATEWAY_MEETING_FORBIDDEN";
    public static final String CODE_INTERNAL = "AUDIO_GATEWAY_INTERNAL";
    // ADR-0031 + Codex `019e8c26` iter-2 AGREE: idempotency conflict + session lifecycle.
    public static final String CODE_IDEMPOTENCY_MISSING = "AUDIO_GATEWAY_IDEMPOTENCY_MISSING";
    public static final String CODE_IDEMPOTENCY_INVALID = "AUDIO_GATEWAY_IDEMPOTENCY_INVALID";
    public static final String CODE_IDEMPOTENCY_CONFLICT = "AUDIO_GATEWAY_IDEMPOTENCY_CONFLICT";
    public static final String CODE_SESSION_NOT_FOUND = "AUDIO_GATEWAY_SESSION_NOT_FOUND";
    public static final String CODE_SESSION_REGISTRY_FULL = "AUDIO_GATEWAY_SESSION_REGISTRY_FULL";
    // PR-gw-01B-core (Codex `019e8d78` iter-2 AGREE) — chunk admission state + ordering.
    public static final String CODE_INVALID_TRANSITION = "AUDIO_GATEWAY_INVALID_TRANSITION";
    public static final String CODE_CHUNK_OUT_OF_ORDER = "AUDIO_GATEWAY_CHUNK_OUT_OF_ORDER";

    public static ErrorResponse of(final String code, final String message, final String correlationId,
                                   final boolean retryable) {
        return new ErrorResponse(code, message, correlationId, retryable, Map.of());
    }
}
