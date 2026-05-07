package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * NotificationInbox — subscriber-addressed in-app inbox row (Faz 23.3 PR-E.1).
 *
 * <p>Independent state machine over {@link NotificationDelivery}:
 * delivery tracks per-channel send attempts ({@code DELIVERED/FAILED/RETRY}),
 * inbox tracks subscriber-side lifecycle ({@code UNREAD → READ → ARCHIVED}).
 * A delivery may be {@code DELIVERED} while the inbox row stays
 * {@code UNREAD} until the subscriber opens the notification.
 *
 * <p>created_at, read_at and archived_at are DB-authoritative (Faz 23.5
 * hardening — Codex thread {@code 019e03b5}). The native UPDATE
 * statements that flip {@code state} also write {@code NOW()} into the
 * timeline columns inside the same statement, so the inbox clock is
 * sourced exclusively from PostgreSQL — never from a JVM. The V9
 * trigger remains as a safety net that drops the timeline markers on
 * backward state transitions.
 *
 * <p>One row per (org_id, intent_id, subscriber_id) — UNIQUE index in DB
 * ensures idempotent insert from intent fan-out / dispatch retries.
 */
@Entity
@Table(name = "notification_inbox", schema = "notify")
public class NotificationInbox {

    public enum State {
        UNREAD, READ, ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id", nullable = false, length = 64)
    private String intentId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "subscriber_id", nullable = false, length = 128)
    private String subscriberId;

    @Column(columnDefinition = "text")
    private String subject;

    @Column(name = "body_text", columnDefinition = "text")
    private String bodyText;

    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Column(nullable = false, length = 16)
    private String locale = "tr-TR";

    @Column(name = "topic_key", nullable = false, length = 128)
    private String topicKey;

    @Column(nullable = false, length = 16)
    private String severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.UNREAD;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /**
     * DB-authoritative creation timestamp (Faz 23.5 hardening — Codex
     * thread `019e03b5`). The column carries {@code DEFAULT NOW()} so
     * every row's {@code created_at} comes from the database clock,
     * which keeps the mark-all-read cutoff predicate race-safe across
     * multiple pods. The application MUST NOT set this field; JPA is
     * told not to insert or update it via {@code insertable=false,
     * updatable=false}.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    // Faz 23.5 hardening (Codex thread `019e03b5`): no @PrePersist hook —
    // created_at is owned by the database, not the JVM clock. Tests that
    // need a specific created_at (e.g. "future row stays UNREAD after
    // mark-all-read") must use raw SQL to set the column.

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public OffsetDateTime getReadAt() { return readAt; }
    public void setReadAt(OffsetDateTime readAt) { this.readAt = readAt; }
    public OffsetDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(OffsetDateTime archivedAt) { this.archivedAt = archivedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationInbox that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
