package com.example.permission.controller;

import com.example.permission.model.ImpersonationSession;
import com.example.permission.service.ImpersonationSessionService;
import com.example.permission.service.ImpersonationSessionService.ActiveSessionExistsException;
import com.example.permission.service.ImpersonationSessionService.ImpersonationConstraintException;
import com.example.permission.service.ImpersonationSessionService.StartSessionRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal REST API — auth-service'in impersonation session yaratması için.
 *
 * <p>Codex 019e0dfb iter-26 mimari: auth-service (identity broker) bu
 * endpoint üzerinden permission-service (session/audit authority)'ye
 * write delegate eder. Auth-service permission DB'ye doğrudan yazmaz.
 *
 * <p>Internal endpoint — gateway public route YOK; sadece cluster içi
 * service-to-service. {@code X-Internal-API-Key} header zorunlu
 * (PERMISSION_SERVICE_INTERNAL_API_KEY env'den).
 */
@RestController
@RequestMapping("/api/v1/internal/impersonation/sessions")
public class InternalImpersonationSessionController {

    private final ImpersonationSessionService sessionService;
    private final String internalApiKey;

    public InternalImpersonationSessionController(
            ImpersonationSessionService sessionService,
            @Value("${security.internal-api-key.value:}") String internalApiKey) {
        this.sessionService = sessionService;
        this.internalApiKey = internalApiKey;
    }

    /**
     * POST /internal/impersonation/sessions
     *
     * <p>auth-service KC token exchange başarılı olduktan sonra exchanged
     * token decode edip bu endpoint'i internal call yapar. Row insert
     * commit olmadan auth-service exchanged token'ı frontend'e dönmemeli
     * (Codex iter-19 critical contract).
     *
     * @return 201 Created with session resource (sessionId + claim binding)
     * @throws 409 Conflict if active session exists (single-active-session)
     * @throws 401 Unauthorized if X-Internal-API-Key missing/wrong
     * @throws 400 Bad Request if validation fails
     */
    @PostMapping
    public ResponseEntity<SessionResource> create(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Internal-API-Key", required = false) String apiKeyLegacy,
            @Valid @RequestBody CreateSessionRequest request) {

        // Codex iter-27 P0 absorb: existing canonical header X-Internal-Api-Key
        // (primary) + legacy fallback X-Internal-API-Key.
        String effectiveKey = apiKey != null ? apiKey : apiKeyLegacy;

        validateInternalApiKey(effectiveKey);

        InetAddress ip = parseInetAddress(request.ipAddress());
        InetAddress xff = parseInetAddress(request.clientIpViaXff());

        StartSessionRequest svcRequest = new StartSessionRequest(
                request.impersonatorUserId(),
                request.impersonatorSubject(),
                request.impersonatorEmail(),
                request.targetUserId(),
                request.targetSubject(),
                request.targetEmail(),
                request.issuer(),
                request.jti(),
                request.sid(),
                request.reason(),
                request.expiresAt(),
                ip,
                request.userAgent(),
                xff);

        ImpersonationSession session = sessionService.startSession(svcRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResource.from(session));
    }

    /**
     * DELETE /internal/impersonation/sessions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> stop(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Internal-API-Key", required = false) String apiKeyLegacy,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Stop-Reason", defaultValue = "USER_STOP") String stopReason) {

        validateInternalApiKey(apiKey != null ? apiKey : apiKeyLegacy);
        boolean stopped = sessionService.stopSession(id, stopReason);
        return stopped ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * GET /api/v1/internal/impersonation/sessions/active?impersonatorUserId=N
     */
    @GetMapping("/active")
    public ResponseEntity<SessionResource> getActive(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Internal-API-Key", required = false) String apiKeyLegacy,
            @org.springframework.web.bind.annotation.RequestParam("impersonatorUserId") Long impersonatorUserId) {

        validateInternalApiKey(apiKey != null ? apiKey : apiKeyLegacy);
        Optional<ImpersonationSession> active = sessionService.getActiveByImpersonator(impersonatorUserId);
        return active.map(s -> ResponseEntity.ok(SessionResource.from(s)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * POST /api/v1/internal/impersonation/sessions/{id}/revoke
     *
     * <p>Admin force-revoke of an active session. Distinct from
     * {@link #stop(String, String, UUID, String)}: revoke is the
     * "operator emergency stop" path — used when an admin needs to
     * terminate a session out-of-band (compromised actor, audit response,
     * incident handling). Audit row carries
     * {@code IMPERSONATION_REVOKED} with the operator-supplied reason.
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Internal-API-Key", required = false) String apiKeyLegacy,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Revoke-Reason", defaultValue = "ADMIN_REVOKE") String revokeReason,
            // Codex iter-27 P1 absorb: operator identity propagated by
            // auth-service so audit row records the revoking SuperAdmin
            // as performedBy, not the impersonator from session snapshot.
            @RequestHeader(value = "X-Operator-User-Id", required = false) String operatorUserId,
            @RequestHeader(value = "X-Operator-Subject", required = false) String operatorSubject,
            @RequestHeader(value = "X-Operator-Email", required = false) String operatorEmail,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        validateInternalApiKey(apiKey != null ? apiKey : apiKeyLegacy);
        Long opUserId = parseLongOrNull(operatorUserId);
        boolean stopped = sessionService.revokeSession(
                id, revokeReason, opUserId, operatorSubject, operatorEmail, correlationId);
        return stopped ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void validateInternalApiKey(String apiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // Service mis-configured — fail-closed
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Internal API key not configured");
        }
        if (apiKey == null || !internalApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid X-Internal-API-Key");
        }
    }

    private InetAddress parseInetAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @ExceptionHandler(ActiveSessionExistsException.class)
    public ResponseEntity<ErrorResponse> handleActiveExists(ActiveSessionExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    /**
     * Codex iter-27 P1 absorb: non-active-session DB constraint violations
     * (no_self / no_self_subject / expires_after_start / status_check) →
     * 400 BadRequest instead of 500.
     */
    @ExceptionHandler(ImpersonationConstraintException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ImpersonationConstraintException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    /**
     * Request body — auth-service KC token exchange sonrası decoded claims
     * + admin context.
     */
    public record CreateSessionRequest(
            @NotNull Long impersonatorUserId,
            @NotBlank @Size(max = 255) String impersonatorSubject,
            @Size(max = 255) String impersonatorEmail,
            @NotNull Long targetUserId,
            @NotBlank @Size(max = 255) String targetSubject,
            @Size(max = 255) String targetEmail,
            @NotBlank @Size(max = 255) String issuer,
            @NotBlank @Size(max = 255) String jti,
            @NotBlank @Size(max = 255) String sid,
            @NotBlank @Size(min = 10, max = 500) String reason,
            @NotNull Instant expiresAt,
            String ipAddress,
            String userAgent,
            String clientIpViaXff) {
    }

    /**
     * Response body — bare-minimum session metadata.
     * Token claim'leri + IP gibi PII'leri auth-service'e geri DÖNDÜRMEZ.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResource(
            UUID sessionId,
            Long impersonatorUserId,
            Long targetUserId,
            Instant startedAt,
            Instant expiresAt,
            String status) {

        public static SessionResource from(ImpersonationSession s) {
            return new SessionResource(
                    s.getId(),
                    s.getImpersonatorUserId(),
                    s.getTargetUserId(),
                    s.getStartedAt(),
                    s.getExpiresAt(),
                    s.getStatus().name());
        }
    }

    public record ErrorResponse(String errorCode, String message) {
    }
}
