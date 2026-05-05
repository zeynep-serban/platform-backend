package com.serban.notify.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Notification intent — domain transactional outbox payload (D38 D46 #1 must-have).
 *
 * Intent lifecycle: PENDING → PROCESSING → COMPLETED|EXPIRED.
 * Each intent fans out to N notification_delivery rows (per channel).
 *
 * Detail: platform-k8s-gitops/docs/notify/event-contract.md §2 + §4
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters/setters omitted for brevity in skeleton — Lombok or full body Faz 23.1
    // implementation hafta 1'de eklenir.
}
