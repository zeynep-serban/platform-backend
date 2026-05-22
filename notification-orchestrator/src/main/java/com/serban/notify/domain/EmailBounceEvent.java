package com.serban.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Email bounce / spam-complaint event dedupe ledger row (Faz 23.8 M7
 * T4.3.b table V17 — first consumer T4.3.5 FBL, Codex 019e4fc6).
 *
 * <p>Thin JPA binding entity over {@code notify.email_bounce_event}.
 * Primary key {@code event_fingerprint} provides idempotency: the same
 * DSN / provider webhook / ARF report processed twice produces the same
 * fingerprint, and the {@code INSERT ... ON CONFLICT DO NOTHING}
 * (see {@link com.serban.notify.repository.EmailBounceEventRepository#insertIfAbsent})
 * makes the second attempt a no-op.
 *
 * <p>{@code source} / {@code classification} are persisted as plain
 * strings; the authoritative value set is enforced by the V17 + V22
 * table CHECK constraints, not the JPA layer (binding entity, not a
 * domain-behavior entity).
 *
 * <p>KVKK: {@code recipient_hash} only — raw email never stored;
 * {@code summary_redacted} carries provider/feedback-type metadata only.
 */
@Entity
@Table(name = "email_bounce_event", schema = "notify")
public class EmailBounceEvent {

    @Id
    @Column(name = "event_fingerprint", length = 128, nullable = false)
    private String eventFingerprint;

    @Column(name = "org_id", length = 64, nullable = false)
    private String orgId;

    @Column(name = "recipient_hash", length = 128, nullable = false)
    private String recipientHash;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "provider_msg_id", length = 255)
    private String providerMsgId;

    /** DSN | PROVIDER_WEBHOOK | MANUAL_API | SMTP_IMMEDIATE | ARF_MAILBOX | POSTMASTER_WEBHOOK. */
    @Column(name = "source", length = 32, nullable = false)
    private String source;

    /** HARD_BOUNCE | SOFT_BOUNCE | SPAM_COMPLAINT | MANUAL. */
    @Column(name = "classification", length = 32, nullable = false)
    private String classification;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "summary_redacted", length = 256)
    private String summaryRedacted;

    public String getEventFingerprint() { return eventFingerprint; }
    public void setEventFingerprint(String eventFingerprint) { this.eventFingerprint = eventFingerprint; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getProviderMsgId() { return providerMsgId; }
    public void setProviderMsgId(String providerMsgId) { this.providerMsgId = providerMsgId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }

    public String getSummaryRedacted() { return summaryRedacted; }
    public void setSummaryRedacted(String summaryRedacted) { this.summaryRedacted = summaryRedacted; }
}
