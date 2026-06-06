package com.example.endpointadmin.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * BE-029 — thrown when a bundle approve request violates maker-checker.
 */
public class SoftwareBundleMakerCheckerViolationException extends ResponseStatusException {

    public SoftwareBundleMakerCheckerViolationException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Maker-checker violation: the bundle approver subject must "
                        + "differ from the creator subject.");
    }
}
