package com.example.endpointadmin.exception;

/**
 * Wave-12 PR-5 (Codex iter-2 absorb) — the request body carried an
 * {@code actor.id} (or {@code proposer.id}) that did not equal the
 * authenticated subject. Closes the body-trust spoofing surface that
 * would otherwise bypass the 4-eyes guard.
 *
 * <p>Mapped to {@code 400 Bad Request} with error code
 * {@code actor_mismatch} by {@code GlobalExceptionHandler} so the
 * platform-web UI can distinguish this from a generic validation
 * failure.
 */
public class PolicyApprovalActorMismatchException extends RuntimeException {

    public PolicyApprovalActorMismatchException(String message) {
        super(message);
    }
}
