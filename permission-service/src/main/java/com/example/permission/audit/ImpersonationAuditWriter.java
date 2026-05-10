package com.example.permission.audit;

import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.service.AuditEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Typed writer for User Impersonation v1 audit events.
 *
 * <p>Codex 019e0dfb iter-27 absorb mandate: IMPERSONATION_STARTED /
 * STOPPED / BLOCKED / FAILED / REVOKED constant'ları sadece string olarak
 * kalmamalı; start/stop/fail path'lerinde gerçekten event yazılmalı ve
 * V19 yeni kolonları (impersonation_session_id, is_impersonated,
 * impersonator_*, target_*, reason) doldurulmalı.
 *
 * <p>Bu writer her event'i tek noktada inşa eder; controller / service
 * katmanları sadece yapı verisi ile çağırır.
 */
@Component
public class ImpersonationAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationAuditWriter.class);
    private static final String SERVICE_NAME = "auth-service";

    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public ImpersonationAuditWriter(AuditEventService auditEventService, ObjectMapper objectMapper) {
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    /**
     * IMPERSONATION_STARTED — successful start (DB session insert committed).
     */
    public PermissionAuditEvent writeStarted(ImpersonationAuditContext ctx, String correlationId) {
        return persist(buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
                "INFO",
                "Impersonation session started",
                ctx,
                correlationId,
                null));
    }

    /**
     * IMPERSONATION_STOPPED — explicit stop or sweep close.
     */
    public PermissionAuditEvent writeStopped(ImpersonationAuditContext ctx,
                                             String correlationId,
                                             String stopReason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (stopReason != null) {
            meta.put("stopReason", stopReason);
        }
        return persist(buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_STOPPED,
                "INFO",
                "Impersonation session stopped",
                ctx,
                correlationId,
                meta));
    }

    /**
     * IMPERSONATION_BLOCKED — pre-DB validation rejection
     * (nested impersonation, insufficient authority, target subject mismatch,
     * exchange azp mismatch, exchange token expired).
     */
    public PermissionAuditEvent writeBlocked(ImpersonationAuditContext ctx,
                                             String correlationId,
                                             String blockReason,
                                             String errorCode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (blockReason != null) {
            meta.put("blockReason", blockReason);
        }
        if (errorCode != null) {
            meta.put("errorCode", errorCode);
        }
        return persist(buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED,
                "WARN",
                "Impersonation request blocked: " + (blockReason == null ? "unspecified" : blockReason),
                ctx,
                correlationId,
                meta));
    }

    /**
     * IMPERSONATION_FAILED — KC token exchange or downstream session
     * persistence failed (technical error, not a policy block).
     */
    public PermissionAuditEvent writeFailed(ImpersonationAuditContext ctx,
                                            String correlationId,
                                            String failureReason,
                                            String errorCode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (failureReason != null) {
            meta.put("failureReason", failureReason);
        }
        if (errorCode != null) {
            meta.put("errorCode", errorCode);
        }
        return persist(buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_FAILED,
                "ERROR",
                "Impersonation request failed: " + (failureReason == null ? "unspecified" : failureReason),
                ctx,
                correlationId,
                meta));
    }

    /**
     * IMPERSONATION_REVOKED — admin/system terminate of an active session.
     *
     * <p>Used for system-driven revokes (no operator identity, e.g. fraud
     * detection script). For operator-driven revokes use
     * {@link #writeRevokedByOperator}.
     */
    public PermissionAuditEvent writeRevoked(ImpersonationAuditContext ctx,
                                             String correlationId,
                                             String revokeReason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (revokeReason != null) {
            meta.put("revokeReason", revokeReason);
        }
        return persist(buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_REVOKED,
                "WARN",
                "Impersonation session revoked",
                ctx,
                correlationId,
                meta));
    }

    /**
     * IMPERSONATION_REVOKED with operator override — Codex iter-27 P1 absorb.
     *
     * <p>Records the revoking SuperAdmin as actor (performedBy/userEmail)
     * while preserving the impersonator/target identity in V19 context
     * columns from the session snapshot. Operator identity goes to
     * metadata as well for forensic clarity.
     */
    public PermissionAuditEvent writeRevokedByOperator(ImpersonationAuditContext ctx,
                                                       String correlationId,
                                                       String revokeReason,
                                                       Long operatorUserId,
                                                       String operatorSubject,
                                                       String operatorEmail) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (revokeReason != null) {
            meta.put("revokeReason", revokeReason);
        }
        if (operatorUserId != null) {
            meta.put("operatorUserId", operatorUserId);
        }
        if (operatorSubject != null) {
            meta.put("operatorSubject", operatorSubject);
        }
        if (operatorEmail != null) {
            meta.put("operatorEmail", operatorEmail);
        }
        PermissionAuditEvent event = buildEvent(
                ImpersonationAuditEventTypes.IMPERSONATION_REVOKED,
                "WARN",
                "Impersonation session revoked by operator",
                ctx,
                correlationId,
                meta);
        // Override actor identity — performedBy + userEmail = revoker
        if (operatorUserId != null) {
            event.setPerformedBy(operatorUserId);
        }
        if (operatorEmail != null && !operatorEmail.isBlank()) {
            event.setUserEmail(operatorEmail);
        } else if (operatorSubject != null && !operatorSubject.isBlank()) {
            event.setUserEmail("subject:" + operatorSubject);
        }
        return persist(event);
    }

    private PermissionAuditEvent buildEvent(String eventType,
                                            String level,
                                            String details,
                                            ImpersonationAuditContext ctx,
                                            String correlationId,
                                            Map<String, Object> extraMeta) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(eventType);
        event.setPerformedBy(ctx.impersonatorUserId());
        event.setDetails(details);
        event.setUserEmail(ctx.impersonatorEmail() != null
                ? ctx.impersonatorEmail()
                : (ctx.impersonatorSubject() != null ? "subject:" + ctx.impersonatorSubject() : null));
        event.setService(SERVICE_NAME);
        event.setLevel(level);
        event.setAction(eventType);
        event.setCorrelationId(correlationId != null ? correlationId : UUID.randomUUID().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (ctx.sessionId() != null) {
            metadata.put("sessionId", ctx.sessionId().toString());
        }
        if (ctx.targetUserId() != null) {
            metadata.put("targetUserId", ctx.targetUserId());
        }
        if (ctx.targetSubject() != null) {
            metadata.put("targetSubject", ctx.targetSubject());
        }
        if (extraMeta != null) {
            metadata.putAll(extraMeta);
        }
        event.setMetadata(writeJsonSafe(metadata));
        event.setBeforeState(null);
        event.setAfterState(null);
        event.setOccurredAt(Instant.now());

        // V19 impersonation context columns
        event.setImpersonationSessionId(ctx.sessionId());
        event.setImpersonated(true);
        event.setImpersonatorUserId(ctx.impersonatorUserId());
        event.setImpersonatorSubject(ctx.impersonatorSubject());
        event.setImpersonatorEmail(ctx.impersonatorEmail());
        event.setTargetUserId(ctx.targetUserId());
        event.setTargetSubject(ctx.targetSubject());
        event.setTargetEmail(ctx.targetEmail());
        event.setImpersonationReason(ctx.reason());

        return event;
    }

    private PermissionAuditEvent persist(PermissionAuditEvent event) {
        try {
            return auditEventService.recordEvent(event);
        } catch (RuntimeException e) {
            log.error("Failed to persist impersonation audit event type={} correlationId={}",
                    event.getEventType(), event.getCorrelationId(), e);
            // Never block business flow on audit failure (best-effort).
            return null;
        }
    }

    private String writeJsonSafe(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize impersonation audit metadata", e);
            return value.toString();
        }
    }

    /**
     * Aggregate context for a single impersonation event write.
     *
     * <p>All fields nullable to support BLOCKED events fired before
     * full identity resolution (e.g. nested impersonation rejection).
     */
    public record ImpersonationAuditContext(
            UUID sessionId,
            Long impersonatorUserId,
            String impersonatorSubject,
            String impersonatorEmail,
            Long targetUserId,
            String targetSubject,
            String targetEmail,
            String reason) {

        public static ImpersonationAuditContext empty() {
            return new ImpersonationAuditContext(null, null, null, null, null, null, null, null);
        }

        public ImpersonationAuditContext withSessionId(UUID sessionId) {
            return new ImpersonationAuditContext(
                    sessionId, impersonatorUserId, impersonatorSubject, impersonatorEmail,
                    targetUserId, targetSubject, targetEmail, reason);
        }
    }
}
