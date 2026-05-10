package com.example.auth.impersonation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * permission-service Internal Impersonation Session API client.
 *
 * <p>Codex 019e0dfb iter-26 mimari split — auth-service identity broker
 * rolünde KC token exchange yapar; bu client permission-service'e
 * (session/audit authority) write delegate eder.
 */
@Component
public class ImpersonationSessionClient {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationSessionClient.class);
    /** Codex iter-27 P1 absorb: cluster-wide hang prevention. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String internalApiKey;

    public ImpersonationSessionClient(
            @Qualifier("plainWebClientBuilder") WebClient.Builder builder,
            @Value("${permission.service.base-url:http://permission-service}") String baseUrl,
            // Codex iter-27 PARTIAL P0 absorb: use existing audit-mirror key
            // convention as primary; permission.internal.api-key + env vars
            // as fallback chain for new deployments. Empty default → fail-closed
            // at request time (validateInternalApiKey rejects).
            @Value("${permission.audit-mirror.internal-api-key:${permission.internal.api-key:${PERMISSION_SERVICE_INTERNAL_API_KEY:}}}") String internalApiKey) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
    }

    /**
     * POST /internal/impersonation/sessions — start session.
     *
     * @return SessionResource on 201
     * @throws ActiveSessionExistsException on 409 (single-active-session policy)
     * @throws ImpersonationSessionClientException on other errors
     */
    public SessionResource startSession(StartRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/impersonation/sessions")
                    .header("X-Internal-Api-Key", internalApiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(SessionResource.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                log.warn("Active impersonation session exists for impersonator={}",
                        request.impersonatorUserId());
                throw new ActiveSessionExistsException("Active impersonation session already exists");
            }
            log.error("permission-service start session failed: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ImpersonationSessionClientException(
                    "Failed to start impersonation session: " + e.getStatusCode(), e);
        } catch (ActiveSessionExistsException | ImpersonationSessionClientException e) {
            throw e;
        } catch (RuntimeException e) {
            // Codex iter-29 P0 absorb: timeout / DNS / connection-reset /
            // WebClientRequestException / Reactor-wrapped failures must wrap
            // as ImpersonationSessionClientException so the controller's
            // SESSION_PERSIST_FAILED audit + 502 branch fires. Without this
            // wrap raw RuntimeException escapes to default Spring 500 handler
            // and IMPERSONATION_FAILED audit row is never written.
            log.error("permission-service start session network/timeout failure: {}", e.getMessage());
            throw new ImpersonationSessionClientException(
                    "permission-service start session unreachable/timeout: " + e.getMessage(), e);
        }
    }

    /**
     * DELETE /internal/impersonation/sessions/{id}
     */
    public boolean stopSession(UUID sessionId, String reason) {
        try {
            webClient.delete()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/internal/impersonation/sessions/{id}").build(sessionId))
                    .header("X-Internal-Api-Key", internalApiKey)
                    .header("X-Stop-Reason", reason)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            log.error("permission-service stop session failed: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ImpersonationSessionClientException(
                    "Failed to stop impersonation session: " + e.getStatusCode(), e);
        } catch (ImpersonationSessionClientException e) {
            throw e;
        } catch (RuntimeException e) {
            // Codex iter-29 P0 absorb: same network/timeout wrap as startSession.
            log.error("permission-service stop session network/timeout failure: {}", e.getMessage());
            throw new ImpersonationSessionClientException(
                    "permission-service stop session unreachable/timeout: " + e.getMessage(), e);
        }
    }

    /**
     * POST /internal/impersonation/sessions/{id}/revoke — admin force-revoke.
     *
     * <p>Codex iter-27 P1 absorb: operator identity (revoking SuperAdmin)
     * propagated via X-Operator-* headers so audit row records actor as
     * the revoker, not the impersonator from the session snapshot.
     */
    public boolean revokeSession(UUID sessionId,
                                 String revokeReason,
                                 Long operatorUserId,
                                 String operatorSubject,
                                 String operatorEmail,
                                 String correlationId) {
        try {
            webClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/internal/impersonation/sessions/{id}/revoke").build(sessionId))
                    .header("X-Internal-Api-Key", internalApiKey)
                    .header("X-Revoke-Reason", revokeReason == null ? "ADMIN_REVOKE" : revokeReason)
                    .header("X-Operator-User-Id", operatorUserId == null ? "" : operatorUserId.toString())
                    .header("X-Operator-Subject", operatorSubject == null ? "" : operatorSubject)
                    .header("X-Operator-Email", operatorEmail == null ? "" : operatorEmail)
                    .header("X-Correlation-Id", correlationId == null ? "" : correlationId)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            log.error("permission-service revoke session failed: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ImpersonationSessionClientException(
                    "Failed to revoke impersonation session: " + e.getStatusCode(), e);
        } catch (ImpersonationSessionClientException e) {
            throw e;
        } catch (RuntimeException e) {
            // Codex iter-29 P0 absorb: same network/timeout wrap.
            log.error("permission-service revoke session network/timeout failure: {}", e.getMessage());
            throw new ImpersonationSessionClientException(
                    "permission-service revoke session unreachable/timeout: " + e.getMessage(), e);
        }
    }

    /**
     * GET /internal/impersonation/sessions/active?impersonatorUserId=N
     */
    public Optional<SessionResource> getActiveByImpersonator(Long impersonatorUserId) {
        try {
            SessionResource result = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/internal/impersonation/sessions/active")
                            .queryParam("impersonatorUserId", impersonatorUserId)
                            .build())
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .bodyToMono(SessionResource.class)
                    .timeout(TIMEOUT)
                    .block();
            return Optional.ofNullable(result);
        } catch (WebClientResponseException e) {
            log.error("permission-service get active session failed: status={}", e.getStatusCode());
            throw new ImpersonationSessionClientException(
                    "Failed to get active session: " + e.getStatusCode(), e);
        } catch (ImpersonationSessionClientException e) {
            throw e;
        } catch (RuntimeException e) {
            // Codex iter-29 P0 absorb: same network/timeout wrap.
            log.error("permission-service get active session network/timeout failure: {}", e.getMessage());
            throw new ImpersonationSessionClientException(
                    "permission-service get active session unreachable/timeout: " + e.getMessage(), e);
        }
    }

    public record StartRequest(
            Long impersonatorUserId,
            String impersonatorSubject,
            String impersonatorEmail,
            Long targetUserId,
            String targetSubject,
            String targetEmail,
            String issuer,
            String jti,
            String sid,
            String reason,
            Instant expiresAt,
            String ipAddress,
            String userAgent,
            String clientIpViaXff) {
    }

    public record SessionResource(
            UUID sessionId,
            Long impersonatorUserId,
            Long targetUserId,
            Instant startedAt,
            Instant expiresAt,
            String status) {
    }

    /**
     * 409 — single-active-session policy violation.
     */
    public static class ActiveSessionExistsException extends RuntimeException {
        public static final String ERROR_CODE = "ACTIVE_IMPERSONATION_EXISTS";

        public ActiveSessionExistsException(String message) {
            super(message);
        }

        public String errorCode() {
            return ERROR_CODE;
        }
    }

    /**
     * Generic permission-service internal API failure (500/timeout/etc).
     */
    public static class ImpersonationSessionClientException extends RuntimeException {
        public ImpersonationSessionClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
