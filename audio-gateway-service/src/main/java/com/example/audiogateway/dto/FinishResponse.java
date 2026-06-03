package com.example.audiogateway.dto;

/**
 * Finish session response — POST /api/v1/audio-gateway/sessions/{sessionId}/finish.
 *
 * <p>Codex {@code 019e8c26} iter-2 AGREE: "Terminal lifecycle event + dispatcher flush
 * contract" — bu PR-gw-01A scope'da transcript üretimi vadetmez; sadece state transition
 * + idempotent finish. Gerçek dispatcher flush PR-gw-01C Redis Streams producer'la gelir.
 *
 * <p>{@code alreadyFinished} = true ise idempotent replay (same Idempotency-Key 2nd call).
 */
public record FinishResponse(
        String sessionId,
        String correlationId,
        String finalState,
        long finishedAtMs,
        boolean alreadyFinished
) {
}
