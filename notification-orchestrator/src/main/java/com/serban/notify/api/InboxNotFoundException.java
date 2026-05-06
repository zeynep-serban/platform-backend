package com.serban.notify.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Inbox row not found — repository returned empty for tenancy-scoped lookup
 * (Faz 23.3 PR-E.1).
 *
 * <p>Returns HTTP 404 (NOT 403); prevents existence disclosure for
 * cross-tenant access attempts (matches IntentNotFoundException pattern).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class InboxNotFoundException extends RuntimeException {
    public InboxNotFoundException(String message) {
        super(message);
    }
}
