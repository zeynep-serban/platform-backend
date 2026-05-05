package com.serban.notify.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Service-layer cross-field validation failure (Codex post-impl bulgu #3, #4 absorb).
 *
 * <p>DTO-level Bean Validation cannot express type-specific recipient field
 * requirements (subscriber.subscriberId vs external.email/phone) without
 * record-level @AssertTrue or custom validator. Service layer catches
 * residual contract violations.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
