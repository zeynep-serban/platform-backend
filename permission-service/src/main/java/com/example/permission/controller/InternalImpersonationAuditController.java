package com.example.permission.controller;

import com.example.permission.audit.ImpersonationAuditWriter;
import com.example.permission.audit.ImpersonationAuditWriter.ImpersonationAuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Internal API for auth-service to record User Impersonation v1
 * BLOCKED / FAILED audit events that never reach a persisted session
 * (e.g. nested impersonation rejected, KC token exchange failed,
 * target subject mismatch).
 *
 * <p>STARTED / STOPPED / REVOKED events are written inline by
 * permission-service ImpersonationSessionService at the moment of
 * DB session lifecycle change — those do NOT go through this endpoint.
 *
 * <p>Codex 019e0dfb iter-27 absorb: audit completeness — auth-service
 * controller must produce events for early-rejection paths.
 *
 * <p>Auth: {@code X-Internal-Api-Key} header (cluster-internal only).
 */
@RestController
@RequestMapping("/api/v1/internal/impersonation/audit-events")
public class InternalImpersonationAuditController {

    private final ImpersonationAuditWriter auditWriter;
    private final String internalApiKey;

    public InternalImpersonationAuditController(
            ImpersonationAuditWriter auditWriter,
            @Value("${security.internal-api-key.value:}") String internalApiKey) {
        this.auditWriter = auditWriter;
        this.internalApiKey = internalApiKey;
    }

    @PostMapping
    public ResponseEntity<Void> ingest(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Internal-API-Key", required = false) String apiKeyLegacy,
            @Valid @RequestBody ImpersonationAuditEventRequest request) {

        validateInternalApiKey(apiKey != null ? apiKey : apiKeyLegacy);

        ImpersonationAuditContext ctx = new ImpersonationAuditContext(
                request.sessionId(),
                request.impersonatorUserId(),
                request.impersonatorSubject(),
                request.impersonatorEmail(),
                request.targetUserId(),
                request.targetSubject(),
                request.targetEmail(),
                request.reason());

        switch (request.eventType()) {
            case "IMPERSONATION_BLOCKED" -> auditWriter.writeBlocked(
                    ctx, request.correlationId(), request.message(), request.errorCode());
            case "IMPERSONATION_FAILED" -> auditWriter.writeFailed(
                    ctx, request.correlationId(), request.message(), request.errorCode());
            case "IMPERSONATION_REVOKED" -> auditWriter.writeRevoked(
                    ctx, request.correlationId(), request.message());
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void validateInternalApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Internal API key not configured");
        }
        if (apiKey == null || !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid X-Internal-API-Key");
        }
    }

    /**
     * Request payload — auth-service ImpersonationAuditClient'tan gelir.
     */
    public record ImpersonationAuditEventRequest(
            @NotBlank String eventType,
            UUID sessionId,
            Long impersonatorUserId,
            String impersonatorSubject,
            String impersonatorEmail,
            Long targetUserId,
            String targetSubject,
            String targetEmail,
            String reason,
            String correlationId,
            String errorCode,
            String message) {
    }
}
