package com.serban.notify.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Cross-org access deny — Codex 019df9ae non-neg #1 absorb.
 *
 * <p>Intent's {@code org_id} mismatches caller's authenticated org context.
 * Repository {@code findByIntentIdAndOrgId} returns empty → controller throws.
 * D41 multi-tenant boundary enforcement.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CrossOrgAccessException extends RuntimeException {
    public CrossOrgAccessException(String message) {
        super(message);
    }
}
