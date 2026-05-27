package com.example.endpointadmin.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * BE-020 — thrown when an approve request fails the catalog maker-checker
 * invariant ({@code approved_by_subject == created_by_subject}).
 *
 * <p>This exception is the single allowed escape from a
 * {@code @Transactional(noRollbackFor = CatalogMakerCheckerViolationException.class)}
 * boundary so the
 * {@code ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit row, emitted just before the throw, survives the failed approve
 * transaction. Maps to HTTP 422 (Unprocessable Entity).
 *
 * <p>Pattern reuse: BE-014A maintenance token deny audit hooks
 * (Codex 019e4ed6 absorb).
 */
public class CatalogMakerCheckerViolationException extends ResponseStatusException {

    public CatalogMakerCheckerViolationException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Maker-checker violation: the approver subject must differ "
                        + "from the creator subject.");
    }
}
