package com.example.permission.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permission_audit_events", indexes = {
        @Index(name = "idx_permission_audit_events_type", columnList = "event_type"),
        @Index(name = "idx_permission_audit_events_user", columnList = "user_email"),
        @Index(name = "idx_permission_audit_events_service", columnList = "service"),
        @Index(name = "idx_permission_audit_events_imp_session", columnList = "impersonation_session_id"),
        @Index(name = "idx_permission_audit_events_imp_target", columnList = "target_user_id")
})
public class PermissionAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "details", length = 2000)
    private String details;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "service")
    private String service;

    @Column(name = "level")
    private String level;

    @Column(name = "action")
    private String action;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "before_state", columnDefinition = "TEXT")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "TEXT")
    private String afterState;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    // ── User Impersonation v1 audit context (V19 migration) ──
    // Codex iter-27 mandate: audit completeness — start/stop/fail/blocked
    // path'lerinde event yazımı yeni V19 kolonlarını doldurmadan kapanmış sayılmaz.
    @Column(name = "impersonation_session_id")
    private UUID impersonationSessionId;

    @Column(name = "is_impersonated", nullable = false)
    private boolean impersonated = false;

    @Column(name = "impersonator_user_id")
    private Long impersonatorUserId;

    @Column(name = "impersonator_subject", length = 255)
    private String impersonatorSubject;

    @Column(name = "impersonator_email", length = 255)
    private String impersonatorEmail;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_subject", length = 255)
    private String targetSubject;

    @Column(name = "target_email", length = 255)
    private String targetEmail;

    @Column(name = "impersonation_reason", length = 500)
    private String impersonationReason;

    public PermissionAuditEvent() {
    }

    public PermissionAuditEvent(String eventType, Long performedBy, String details) {
        this.eventType = eventType;
        this.performedBy = performedBy;
        this.details = details;
    }

    public PermissionAuditEvent(String eventType,
                                Long performedBy,
                                String details,
                                String userEmail,
                                String service,
                                String level,
                                String action,
                                String correlationId,
                                String metadata,
                                String beforeState,
                                String afterState) {
        this.eventType = eventType;
        this.performedBy = performedBy;
        this.details = details;
        this.userEmail = userEmail;
        this.service = service;
        this.level = level;
        this.action = action;
        this.correlationId = correlationId;
        this.metadata = metadata;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(Long performedBy) {
        this.performedBy = performedBy;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public void setBeforeState(String beforeState) {
        this.beforeState = beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public void setAfterState(String afterState) {
        this.afterState = afterState;
    }

    public UUID getImpersonationSessionId() {
        return impersonationSessionId;
    }

    public void setImpersonationSessionId(UUID impersonationSessionId) {
        this.impersonationSessionId = impersonationSessionId;
    }

    public boolean isImpersonated() {
        return impersonated;
    }

    public void setImpersonated(boolean impersonated) {
        this.impersonated = impersonated;
    }

    public Long getImpersonatorUserId() {
        return impersonatorUserId;
    }

    public void setImpersonatorUserId(Long impersonatorUserId) {
        this.impersonatorUserId = impersonatorUserId;
    }

    public String getImpersonatorSubject() {
        return impersonatorSubject;
    }

    public void setImpersonatorSubject(String impersonatorSubject) {
        this.impersonatorSubject = impersonatorSubject;
    }

    public String getImpersonatorEmail() {
        return impersonatorEmail;
    }

    public void setImpersonatorEmail(String impersonatorEmail) {
        this.impersonatorEmail = impersonatorEmail;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getTargetSubject() {
        return targetSubject;
    }

    public void setTargetSubject(String targetSubject) {
        this.targetSubject = targetSubject;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    public String getImpersonationReason() {
        return impersonationReason;
    }

    public void setImpersonationReason(String impersonationReason) {
        this.impersonationReason = impersonationReason;
    }
}
