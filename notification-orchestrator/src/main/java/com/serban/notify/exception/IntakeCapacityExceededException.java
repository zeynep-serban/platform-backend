package com.serban.notify.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Intake capacity exceeded — Codex 019df9ae Q3 PARTIAL absorb.
 *
 * <p>{@code dispatch.enabled=false} bounded intake mode: if pending intent count
 * exceeds {@code notify.intake.maxPending} threshold, return HTTP 503 Service
 * Unavailable. Prevents unbounded queue depth in test cluster while worker
 * absent (PR4'te eklenecek).
 *
 * <p>Codex non-neg: admin {@code --reset-queue} endpoint <b>YASAK</b>;
 * dangerous prod surface. Bounded intake is the safe alternative.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class IntakeCapacityExceededException extends RuntimeException {
    public IntakeCapacityExceededException(String message) {
        super(message);
    }
}
