package com.example.endpointadmin.exception;

/**
 * Wave-12 PR-5 (Codex iter-2 absorb) — a delegate request violated the
 * current-approver membership invariant. Two failure modes:
 *
 * <ul>
 *   <li>The delegating {@code actor} is NOT currently on the approver
 *       list (would let a non-approver hand-off a request they don't
 *       own);</li>
 *   <li>The {@code delegateTo} target is ALREADY on the approver list
 *       (the in-place swap would create a duplicate).</li>
 * </ul>
 *
 * <p>Mapped to {@code 400 Bad Request} with error code
 * {@code delegate_conflict} by {@code GlobalExceptionHandler}.
 */
public class PolicyApprovalDelegateConflictException extends RuntimeException {

    public PolicyApprovalDelegateConflictException(String message) {
        super(message);
    }
}
