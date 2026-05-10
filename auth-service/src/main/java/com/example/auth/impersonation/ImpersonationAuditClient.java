package com.example.auth.impersonation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Internal client for permission-service impersonation audit ingestion.
 *
 * <p>Codex 019e0dfb iter-27 absorb mandate: BLOCKED / FAILED audit events
 * (nested impersonation rejected, KC token exchange failed, target subject
 * mismatch, exchange azp mismatch, exchange token expired) must reach the
 * audit authority (permission-service) even though no impersonation_sessions
 * row was ever created.
 *
 * <p>Failure handling: best-effort. Audit write failure NEVER blocks the
 * caller's HTTP response. Logged at WARN with correlationId for forensic
 * recovery.
 */
@Component
public class ImpersonationAuditClient {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationAuditClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String internalApiKey;

    public ImpersonationAuditClient(
            @Qualifier("plainWebClientBuilder") WebClient.Builder builder,
            @Value("${permission.service.base-url:http://permission-service}") String baseUrl,
            // Codex iter-27 PARTIAL P0 absorb: align with existing
            // permission.audit-mirror.internal-api-key convention so
            // PERMISSION_SERVICE_INTERNAL_API_KEY env var (already set in
            // .env.prod.example + k8s ConfigMap) flows through.
            @Value("${permission.audit-mirror.internal-api-key:${permission.internal.api-key:${PERMISSION_SERVICE_INTERNAL_API_KEY:}}}") String internalApiKey) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
    }

    public void writeBlocked(AuditPayload payload) {
        write(payload.withType("IMPERSONATION_BLOCKED"));
    }

    public void writeFailed(AuditPayload payload) {
        write(payload.withType("IMPERSONATION_FAILED"));
    }

    public void writeRevoked(AuditPayload payload) {
        write(payload.withType("IMPERSONATION_REVOKED"));
    }

    private void write(AuditPayload payload) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/impersonation/audit-events")
                    .header("X-Internal-Api-Key", internalApiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Impersonation audit write failed: type={} status={} body={} correlationId={}",
                    payload.eventType(), e.getStatusCode(), e.getResponseBodyAsString(),
                    payload.correlationId());
        } catch (RuntimeException e) {
            log.warn("Impersonation audit write error: type={} correlationId={} msg={}",
                    payload.eventType(), payload.correlationId(), e.getMessage());
        }
    }

    /**
     * Audit payload — matches permission-service
     * InternalImpersonationAuditController.ImpersonationAuditEventRequest.
     */
    public record AuditPayload(
            String eventType,
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

        public AuditPayload withType(String type) {
            return new AuditPayload(
                    type, sessionId, impersonatorUserId, impersonatorSubject,
                    impersonatorEmail, targetUserId, targetSubject, targetEmail,
                    reason, correlationId, errorCode, message);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private UUID sessionId;
            private Long impersonatorUserId;
            private String impersonatorSubject;
            private String impersonatorEmail;
            private Long targetUserId;
            private String targetSubject;
            private String targetEmail;
            private String reason;
            private String correlationId;
            private String errorCode;
            private String message;

            public Builder sessionId(UUID v) { this.sessionId = v; return this; }
            public Builder impersonatorUserId(Long v) { this.impersonatorUserId = v; return this; }
            public Builder impersonatorSubject(String v) { this.impersonatorSubject = v; return this; }
            public Builder impersonatorEmail(String v) { this.impersonatorEmail = v; return this; }
            public Builder targetUserId(Long v) { this.targetUserId = v; return this; }
            public Builder targetSubject(String v) { this.targetSubject = v; return this; }
            public Builder targetEmail(String v) { this.targetEmail = v; return this; }
            public Builder reason(String v) { this.reason = v; return this; }
            public Builder correlationId(String v) { this.correlationId = v; return this; }
            public Builder errorCode(String v) { this.errorCode = v; return this; }
            public Builder message(String v) { this.message = v; return this; }

            public AuditPayload build() {
                return new AuditPayload(null, sessionId, impersonatorUserId,
                        impersonatorSubject, impersonatorEmail, targetUserId,
                        targetSubject, targetEmail, reason, correlationId,
                        errorCode, message);
            }
        }
    }
}
