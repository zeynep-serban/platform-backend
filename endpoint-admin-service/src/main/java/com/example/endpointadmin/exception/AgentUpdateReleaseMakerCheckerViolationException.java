package com.example.endpointadmin.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * BE-031 — thrown when an agent update release approval violates maker-checker.
 */
public class AgentUpdateReleaseMakerCheckerViolationException
        extends ResponseStatusException {

    public AgentUpdateReleaseMakerCheckerViolationException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Maker-checker violation: the release approver subject must "
                        + "differ from the creator subject.");
    }
}
