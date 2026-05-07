package com.example.permission.dto.v1;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public class AuditEventIngestRequestDto {

    @NotBlank
    private String eventType;

    /**
     * Numeric user id of the actor performing the audited event, when the
     * caller has resolved one. Optional because some upstream services
     * (e.g. report-service) only have the JWT {@code sub} claim, which is a
     * UUID for Keycloak realm users — no numeric mapping is available
     * synchronously at the audit dispatch site. The DB column
     * {@code permission_audit_events.performed_by} is also nullable, so a
     * mirrored event with {@code null} performedBy is a valid record;
     * {@code userEmail} + {@code correlationId} carry the actor identity
     * when this field is absent.
     *
     * <p>Removing the previous {@code @NotNull} also closes a live
     * {@code 400 VALIDATION_ERROR} loop on
     * {@code POST /api/v1/internal/audit/events} where every
     * {@code REPORT_ACCESS} mirror was being rejected; report-service
     * caught the failure but the audit trail was lost.
     */
    private Long performedBy;

    private String details;

    @NotBlank
    private String userEmail;

    @NotBlank
    private String service;

    @NotBlank
    private String level;

    @NotBlank
    private String action;

    private String correlationId;

    private Map<String, Object> metadata;

    private Map<String, Object> before;

    private Map<String, Object> after;

    private Instant occurredAt;

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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public void setBefore(Map<String, Object> before) {
        this.before = before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public void setAfter(Map<String, Object> after) {
        this.after = after;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
