package com.example.endpointadmin.exception;

import com.example.endpointadmin.dto.ErrorResponse;
import com.example.endpointadmin.dto.ErrorResponse.FieldError;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.security.DeviceCredentialException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DeviceCredentialException.class)
    public ResponseEntity<ErrorResponse> handleDeviceCredential(DeviceCredentialException ex) {
        return build(HttpStatus.UNAUTHORIZED, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        String message = ex.getReason() != null ? ex.getReason() : "Unexpected error.";
        return build(status, code, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        List<FieldError> fieldErrors = bindingResult.getFieldErrors()
                .stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", fieldErrors);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    /**
     * BE-020 PR-B (Codex 019e6aa8 iter-1 absorb): {@code @RequestParam} or
     * {@code @PathVariable} type / enum conversion failures (e.g.
     * {@code ?status=BOGUS}, {@code ?page=abc}) must surface as 400 with a
     * named parameter, not the generic {@code Exception} catch-all 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "?";
        String message = "Invalid value for parameter '" + paramName
                + "' (expected " + requiredType + ").";
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    /**
     * BE-020 PR-B (Codex 019e6aa8 iter-1 absorb): request body JSON parse
     * failures (malformed JSON, unknown enum values inside {@code @RequestBody})
     * must surface as 400, not 500. Spring throws
     * {@link HttpMessageNotReadableException} for both shapes.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBodyParseError(
            HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Request body could not be parsed.");
    }

    /**
     * BE-021A (Codex 019e6b88 iter-1 absorb): missing
     * {@code @RequestParam} (e.g. {@code catalogItemId} omitted from the
     * install-preflight GET) must surface as 400, not the generic
     * {@code Exception} catch-all 500. Spring throws
     * {@link org.springframework.web.bind.MissingServletRequestParameterException}.
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        String paramName = ex.getParameterName();
        String paramType = ex.getParameterType();
        String message = "Missing required parameter '" + paramName
                + "' (" + paramType + ").";
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", message);
    }

    /**
     * BE-021 — dedicated install path returns the canonical
     * {@link InstallPreflightResponse} body when preflight recompute
     * yields BLOCK. Status is 409 Conflict so the UI can treat it as a
     * recoverable "fix the device / catalog and retry" outcome
     * (Codex 019e6dfb iter-3 P1-2).
     */
    @ExceptionHandler(InstallBlockedException.class)
    public ResponseEntity<InstallPreflightResponse> handleInstallBlocked(
            InstallBlockedException ex) {
        InstallPreflightResponse body = ex.preflight();
        log.warn("install_blocked decision={} catalogItemId={} deviceId={} reasons={}",
                body.decision(), body.catalogItemId(), body.deviceId(),
                body.blockingReasons());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Wave-12 PR-5 — 4-eyes governance violation: the proposer of a
     * policy-change approval request tried to approve their own request.
     * Surfaced as {@code 403 Forbidden} with error code
     * {@code proposer_self} so the platform-web UI can distinguish this
     * from a generic authorisation failure.
     */
    @ExceptionHandler(PolicyApprovalProposerSelfException.class)
    public ResponseEntity<ErrorResponse> handleProposerSelf(
            PolicyApprovalProposerSelfException ex) {
        return build(HttpStatus.FORBIDDEN, "proposer_self", ex.getMessage());
    }

    /**
     * Wave-12 PR-5 (Codex iter-2 absorb) — body-supplied {@code actor.id}
     * (or {@code proposer.id} on propose) did not equal the
     * authenticated subject. Surfaced as {@code 400 Bad Request} with a
     * stable error code so the UI can show a "your session does not
     * match the actor in the request" hint rather than a generic
     * validation error.
     */
    @ExceptionHandler(PolicyApprovalActorMismatchException.class)
    public ResponseEntity<ErrorResponse> handleActorMismatch(
            PolicyApprovalActorMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "actor_mismatch", ex.getMessage());
    }

    /**
     * Wave-12 PR-5 (Codex iter-2 absorb) — delegate violated the
     * current-approver membership invariant (delegating actor not on
     * the list, or delegateTo already on the list). Surfaced as
     * {@code 400 Bad Request} with the stable {@code delegate_conflict}
     * code so the UI can highlight the offending actor.
     */
    @ExceptionHandler(PolicyApprovalDelegateConflictException.class)
    public ResponseEntity<ErrorResponse> handleDelegateConflict(
            PolicyApprovalDelegateConflictException ex) {
        return build(HttpStatus.BAD_REQUEST, "delegate_conflict", ex.getMessage());
    }

    /**
     * Wave-12 PR-5 (Codex iter-1 P1 absorb + iter-2 narrowing) —
     * concurrent reviewers race on the same approval request. With
     * {@code PESSIMISTIC_WRITE} on the decision read path the loser
     * should observe the new state and raise a domain 409 via the
     * "not open" status check. The handler covers the residual surface
     * where Hibernate translates a lock acquisition failure into a
     * Spring lock-failure exception (timeout, dead-lock, etc.).
     *
     * <p>{@code DataIntegrityViolationException} is intentionally NOT
     * caught here — that class also raises on legitimate constraint
     * failures in unrelated endpoint-admin paths (compliance policy
     * persist, HMAC nonce replay, machine-cert enrollment race) and
     * mis-labelling those as {@code concurrent_decision} would mask
     * real bugs. The unique-on-sequence path in the decision flow is
     * already protected by the pessimistic lock; should it ever surface
     * a constraint hit, the generic 500 plus warn-log is the correct
     * fail-loud behaviour (Codex iter-2 P2 absorb).
     */
    @ExceptionHandler({OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConcurrentApprovalRace(
            RuntimeException ex) {
        log.warn("concurrent approval race: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "concurrent_decision",
                "Another reviewer decided this approval request first. "
                        + "Refresh and retry.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        return build(status, error, message, ErrorResponse.emptyFieldErrors());
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String error,
            String message,
            List<FieldError> fieldErrors
    ) {
        String traceId = resolveTraceId();
        ErrorResponse response = ErrorResponse.of(error, message, fieldErrors, traceId);
        log.warn("error={} status={} traceId={} message={}", error, status.value(), traceId, message);
        return ResponseEntity.status(status).body(response);
    }

    private String resolveTraceId() {
        String existing = MDC.get("traceId");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put("traceId", generated);
        return generated;
    }
}
