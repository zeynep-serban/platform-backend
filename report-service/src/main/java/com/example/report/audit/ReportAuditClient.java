package com.example.report.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class ReportAuditClient {

    private static final Logger log = LoggerFactory.getLogger(ReportAuditClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final WebClient webClient;
    private final String internalApiKey;

    public ReportAuditClient(@Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                             @Value("${permission.service.base-url:http://permission-service}") String baseUrl,
                             @Value("${report.audit.internal-api-key:}") String internalApiKey) {
        // D7: @LoadBalanced kaldırıldı — K8s DNS ile plain builder yeterli.
        this.webClient = plainWebClientBuilder.baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
    }

    public void logReportAccess(String reportKey, String userId, String userEmail) {
        sendAuditEvent("REPORT_ACCESS", userId, userEmail,
                reportKey + " report accessed",
                Map.of("reportKey", reportKey));
    }

    public void logReportExport(String reportKey, String userId, String userEmail, String format) {
        sendAuditEvent("REPORT_EXPORT", userId, userEmail,
                reportKey + " report exported as " + format,
                Map.of("reportKey", reportKey, "format", format));
    }

    public void logReportAccessDenied(String reportKey, String userId, String userEmail, String reason) {
        sendAuditEvent("REPORT_ACCESS_DENIED", userId, userEmail,
                reportKey + " report access denied: " + reason,
                Map.of("reportKey", reportKey, "reason", reason));
    }

    private void sendAuditEvent(String eventType, String userId, String userEmail,
                                 String details, Map<String, Object> metadata) {
        if (!StringUtils.hasText(internalApiKey)) {
            return;
        }

        /*
         * Resolve the numeric performedBy when the upstream userId is a
         * Long (e.g. user-service / auth-service principals). Keycloak
         * realm UUIDs (super-admin and similar) fall through the catch,
         * leaving performedBy=null. Codex iter-1 absorb: in that case
         * we MUST still persist the immutable actor identifier in audit
         * metadata so the audit DB row isn't anonymous (userEmail can
         * change; correlationId is just a trace bond).
         */
        Long performedBy = null;
        String actorKind = "unknown";
        try {
            performedBy = Long.parseLong(userId);
            actorKind = "numeric_user_id";
        } catch (NumberFormatException e) {
            if (StringUtils.hasText(userId)) {
                actorKind = "keycloak_sub";
            }
        }

        Map<String, Object> auditMetadata = new java.util.LinkedHashMap<>();
        if (metadata != null) {
            auditMetadata.putAll(metadata);
        }
        if (StringUtils.hasText(userId)) {
            // Stable, immutable identifier of the actor — survives email
            // changes and is the canonical reference into authz/me. The
            // audit read API already redacts strings under "identifier"
            // keys, so PII risk stays bounded.
            auditMetadata.put("actorIdentifier", userId);
            auditMetadata.put("actorKind", actorKind);
        }

        var request = new AuditEventIngestRequest(
                eventType,
                performedBy,
                details,
                userEmail,
                "report-service",
                "INFO",
                eventType,
                resolveCorrelationId(),
                auditMetadata,
                null,
                null,
                Instant.now()
        );

        try {
            var spec = webClient.post()
                    .uri("/api/v1/internal/audit/events")
                    .header(INTERNAL_API_KEY_HEADER, internalApiKey)
                    .contentType(MediaType.APPLICATION_JSON);
            String traceId = MDC.get("traceId");
            if (StringUtils.hasText(traceId)) {
                spec.header(TRACE_ID_HEADER, traceId);
            }
            spec.bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("Report audit mirror HTTP error: status={} message={}", ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("Report audit mirror failed: {}", ex.getMessage());
        }
    }

    private String resolveCorrelationId() {
        String traceId = MDC.get("traceId");
        return StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
    }

    private record AuditEventIngestRequest(
            String eventType,
            Long performedBy,
            String details,
            String userEmail,
            String service,
            String level,
            String action,
            String correlationId,
            Map<String, Object> metadata,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant occurredAt
    ) {}
}
