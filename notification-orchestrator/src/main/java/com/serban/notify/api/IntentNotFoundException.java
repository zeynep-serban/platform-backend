package com.serban.notify.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Intent not found — repository returned empty for org-scoped lookup.
 *
 * <p>Codex non-neg #1: returns HTTP 404 (NOT 403), prevents existence
 * disclosure for cross-tenant access attempts.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class IntentNotFoundException extends RuntimeException {
    public IntentNotFoundException(String message) {
        super(message);
    }
}
