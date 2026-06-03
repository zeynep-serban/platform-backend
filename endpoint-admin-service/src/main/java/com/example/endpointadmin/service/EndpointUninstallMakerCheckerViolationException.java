package com.example.endpointadmin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * AG-028 Phase 1b — maker-checker violation during a managed uninstall
 * request approval (Faz 22.5.6).
 *
 * <p>Thrown by {@link EndpointUninstallService#approve} when the approver
 * subject equals the proposer (createdBy) subject. Mirrors the Phase 0
 * pattern
 * ({@link CatalogUninstallSettingsMakerCheckerViolationException}).
 *
 * <p>Subclasses {@link ResponseStatusException} (not just a
 * {@code @ResponseStatus(FORBIDDEN)} marker) because the repo's
 * {@code GlobalExceptionHandler} has a catch-all
 * {@code @ExceptionHandler(Exception.class)} that would otherwise map
 * this to HTTP 500. The Spring MVC dispatcher resolves a
 * {@code ResponseStatusException} explicitly to its declared status
 * code, bypassing the catch-all (Codex Phase 0 iter-2 absorb).
 *
 * <p>BE-014A {@code noRollbackFor} pattern: the service annotation
 * excludes this exception from rollback so the durable
 * {@code ENDPOINT_UNINSTALL_REQUEST_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit row written before the throw survives the rejected
 * transaction.
 *
 * <p>Maps to HTTP 403 (Forbidden).
 */
public class EndpointUninstallMakerCheckerViolationException
        extends ResponseStatusException {

    private final UUID requestId;
    private final String createdBy;
    private final String approverSubject;

    public EndpointUninstallMakerCheckerViolationException(
            UUID requestId, String createdBy, String approverSubject) {
        super(HttpStatus.FORBIDDEN,
                "Maker-checker violation: uninstall approver must differ from proposer "
                        + "(requestId=" + requestId + ").");
        this.requestId = requestId;
        this.createdBy = createdBy;
        this.approverSubject = approverSubject;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getApproverSubject() {
        return approverSubject;
    }
}
