package com.serban.notify.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Notification intent — domain transactional outbox payload (Faz 23.1, ADR-0013 D46 #1).
 *
 * <p>Lifecycle: PENDING → PROCESSING → COMPLETED|EXPIRED. Each intent fans out
 * to N notification_delivery rows (per channel per recipient).
 *
 * <p>Detail: platform-k8s-gitops/docs/notify/event-contract.md
 */
@Entity
@Table(name = "notification_intent", schema = "notify")
public class NotificationIntent {

    public enum Status { PENDING, PROCESSING, COMPLETED, EXPIRED }

    public enum Severity { info, warning, critical }

    public enum DataClassification {
        transactional, security, commercial, system
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id", nullable = false, unique = true, length = 64)
    private String intentId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "topic_key", nullable = false, length = 128)
    private String topicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_classification", nullable = false, length = 16)
    private DataClassification dataClassification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "template_id", nullable = false, length = 128)
    private String templateId;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] channels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_routing", columnDefinition = "jsonb")
    private Map<String, Object> channelRouting;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "expire_at")
    private OffsetDateTime expireAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_override", columnDefinition = "jsonb")
    private Map<String, Object> preferenceOverride;

    /**
     * Recipients snapshot — submit-time persisted (Codex 019df9ef P2 absorb).
     *
     * <p>Email channel fan-out (N targets per intent) requires recipients list
     * at PR4 worker time. Slack/webhook channels are target-addressed (1 target
     * via channel_routing) and do not depend on this column.
     *
     * <p>Each entry: {@code {type, subscriberId, email, phone, name, locale}}.
     * Stored as List in JSONB column for PG/JSON friendliness.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipients_snapshot", columnDefinition = "jsonb")
    private java.util.List<Map<String, Object>> recipientsSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = Status.PENDING;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public DataClassification getDataClassification() { return dataClassification; }
    public void setDataClassification(DataClassification dataClassification) {
        this.dataClassification = dataClassification;
    }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public Integer getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(Integer templateVersion) { this.templateVersion = templateVersion; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public String[] getChannels() { return channels; }
    public void setChannels(String[] channels) { this.channels = channels; }

    public Map<String, Object> getChannelRouting() { return channelRouting; }
    public void setChannelRouting(Map<String, Object> channelRouting) {
        this.channelRouting = channelRouting;
    }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public OffsetDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(OffsetDateTime expireAt) { this.expireAt = expireAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Map<String, Object> getPreferenceOverride() { return preferenceOverride; }
    public void setPreferenceOverride(Map<String, Object> preferenceOverride) {
        this.preferenceOverride = preferenceOverride;
    }

    public java.util.List<Map<String, Object>> getRecipientsSnapshot() { return recipientsSnapshot; }
    public void setRecipientsSnapshot(java.util.List<Map<String, Object>> recipientsSnapshot) {
        this.recipientsSnapshot = recipientsSnapshot;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationIntent that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode(); // Codex post-impl bulgu fix: id-null collision avoid; entity uniqueness via id equals
    }
}
