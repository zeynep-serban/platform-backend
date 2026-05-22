package com.serban.notify.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Email recipient suppression entry (Faz 23.8 M7 T4.3.b — Codex
 * `019e492f` AGREE partial MVP).
 *
 * <p>Provider IP reputation koruma: hard-bounce / spam-complaint /
 * soft-bounce-repeated alıcılar burada listelenir. Send pipeline
 * {@code DeliveryEligibilityService} her email dispatch öncesi bu
 * tabloyu sorar; match varsa adapter çağrılmaz, delivery row
 * {@code BLOCKED_BY_SUPPRESSION} terminal status ile yaratılır.
 *
 * <p>KVKK uyumu: raw email asla saklanmaz — sadece {@code
 * recipient_hash} (canonical SHA-256 of normalized email). Listeden
 * çıkarma (release) manual admin API ile yapılır; otomatik
 * "suppress + forget" akışı çift güvenlik sağlar.
 *
 * <p>Composite primary key: {@code (org_id, channel, recipient_hash)}.
 * {@code channel} her zaman {@code "email"} (DB CHECK constraint);
 * şu an tek kanal için ama gelecekte SMS suppression eklenirse
 * channel discriminator hazır.
 */
@Entity
@Table(name = "email_suppression", schema = "notify")
@IdClass(EmailSuppression.Id.class)
public class EmailSuppression {

    public enum Reason {
        HARD_BOUNCE,
        SOFT_BOUNCE_REPEATED,
        SPAM_COMPLAINT,
        MANUAL
    }

    public enum RecipientType {
        SUBSCRIBER,
        EXTERNAL
    }

    public enum Source {
        DSN,
        PROVIDER_WEBHOOK,
        MANUAL_API,
        SMTP_IMMEDIATE,
        /** Faz 23.8 M7 T4.3.5 — Office 365 Postmaster ARF mailbox-pull FBL. */
        ARF_MAILBOX,
        /** Faz 23.8 M7 T4.3.5 — forward-compat webhook-push FBL (no PR-1 caller). */
        POSTMASTER_WEBHOOK
    }

    @jakarta.persistence.Id
    @Column(name = "org_id", length = 64, nullable = false)
    private String orgId;

    @jakarta.persistence.Id
    @Column(name = "channel", length = 32, nullable = false)
    private String channel = "email";

    @jakarta.persistence.Id
    @Column(name = "recipient_hash", length = 128, nullable = false)
    private String recipientHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", length = 32, nullable = false)
    private RecipientType recipientType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32, nullable = false)
    private Reason reason;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "bounce_count", nullable = false)
    private int bounceCount = 1;

    @Column(name = "soft_window_started_at")
    private OffsetDateTime softWindowStartedAt;

    @Column(name = "suppressed_until")
    private OffsetDateTime suppressedUntil;

    @Column(name = "last_bounce_summary_redacted", length = 256)
    private String lastBounceSummaryRedacted;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_source", length = 32)
    private Source lastSource;

    @Column(name = "last_provider", length = 64)
    private String lastProvider;

    // V22 widened last_provider_msg_id 128 -> 255 (align with
    // notification_delivery.provider_msg_id); entity mapping kept in sync so
    // Hibernate ddl-auto=validate does not fail context startup
    // (Codex 019e4fc6 iter-2 HIGH #2).
    @Column(name = "last_provider_msg_id", length = 255)
    private String lastProviderMsgId;

    @Column(name = "last_event_fingerprint", length = 128)
    private String lastEventFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    /** Composite primary key holder. */
    public static class Id implements Serializable {
        private String orgId;
        private String channel;
        private String recipientHash;

        public Id() {}

        public Id(String orgId, String channel, String recipientHash) {
            this.orgId = orgId;
            this.channel = channel;
            this.recipientHash = recipientHash;
        }

        public String getOrgId() { return orgId; }
        public void setOrgId(String orgId) { this.orgId = orgId; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getRecipientHash() { return recipientHash; }
        public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id)) return false;
            Id id = (Id) o;
            return Objects.equals(orgId, id.orgId)
                && Objects.equals(channel, id.channel)
                && Objects.equals(recipientHash, id.recipientHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, channel, recipientHash);
        }
    }

    /**
     * Returns true if the suppression entry should currently block
     * dispatch. A row is "active" when:
     * <ul>
     *   <li>HARD_BOUNCE / SPAM_COMPLAINT / SOFT_BOUNCE_REPEATED with
     *       {@code suppressed_until = NULL} (permanent), OR</li>
     *   <li>{@code suppressed_until > now} (still within soft hold)</li>
     * </ul>
     */
    public boolean isCurrentlyActive(OffsetDateTime now) {
        if (suppressedUntil == null) {
            // permanent (HARD_BOUNCE / SPAM_COMPLAINT / MANUAL permanent)
            return true;
        }
        return suppressedUntil.isAfter(now);
    }

    // ─── Getters / Setters ──────────────────────────────────────────────

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }

    public RecipientType getRecipientType() { return recipientType; }
    public void setRecipientType(RecipientType recipientType) { this.recipientType = recipientType; }

    public Reason getReason() { return reason; }
    public void setReason(Reason reason) { this.reason = reason; }

    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public int getBounceCount() { return bounceCount; }
    public void setBounceCount(int bounceCount) { this.bounceCount = bounceCount; }

    public OffsetDateTime getSoftWindowStartedAt() { return softWindowStartedAt; }
    public void setSoftWindowStartedAt(OffsetDateTime softWindowStartedAt) { this.softWindowStartedAt = softWindowStartedAt; }

    public OffsetDateTime getSuppressedUntil() { return suppressedUntil; }
    public void setSuppressedUntil(OffsetDateTime suppressedUntil) { this.suppressedUntil = suppressedUntil; }

    public String getLastBounceSummaryRedacted() { return lastBounceSummaryRedacted; }
    public void setLastBounceSummaryRedacted(String lastBounceSummaryRedacted) { this.lastBounceSummaryRedacted = lastBounceSummaryRedacted; }

    public Source getLastSource() { return lastSource; }
    public void setLastSource(Source lastSource) { this.lastSource = lastSource; }

    public String getLastProvider() { return lastProvider; }
    public void setLastProvider(String lastProvider) { this.lastProvider = lastProvider; }

    public String getLastProviderMsgId() { return lastProviderMsgId; }
    public void setLastProviderMsgId(String lastProviderMsgId) { this.lastProviderMsgId = lastProviderMsgId; }

    public String getLastEventFingerprint() { return lastEventFingerprint; }
    public void setLastEventFingerprint(String lastEventFingerprint) { this.lastEventFingerprint = lastEventFingerprint; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
