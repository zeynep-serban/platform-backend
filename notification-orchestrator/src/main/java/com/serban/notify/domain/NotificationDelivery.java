package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Notification delivery — per-channel attempt tracking. ADR-0013 D46 #1, #4.
 */
@Entity
@Table(name = "notification_delivery", schema = "notify")
public class NotificationDelivery {

    public enum Status {
        PENDING,
        /**
         * Faz 23.4 PR-F: provider accepted message for delivery (carrier
         * queued); awaiting terminal DLR webhook to transition to DELIVERED
         * or FAILED. Used by SMS adapters (NetGSM, İletimerkezi) where
         * send response ≠ terminal delivery. Synchronous channels
         * (email SMTP, slack, webhook, in-app) skip ACCEPTED — go directly
         * to DELIVERED on send success.
         */
        ACCEPTED,
        DELIVERED, FAILED, BOUNCED, RETRY,
        BLOCKED_BY_PREFERENCE, BLOCKED_BY_AUTHZ, BLOCKED_BY_IDEMPOTENCY,
        BLOCKED_EXTERNAL_NOT_ALLOWED
    }

    /**
     * Recipient type semantics:
     * <ul>
     *   <li>{@code SUBSCRIBER} — internal user (subscriber_id)</li>
     *   <li>{@code EXTERNAL} — external person (email/phone)</li>
     *   <li>{@code CHANNEL} — target-addressed channel (Codex 019df9ef absorb):
     *       slack/webhook target where there is no individual recipient (e.g.,
     *       org-level Slack channel webhook URL). Audit context: N recipients
     *       receive the message via the channel, but only 1 delivery row exists.</li>
     * </ul>
     */
    public enum RecipientType { SUBSCRIBER, EXTERNAL, CHANNEL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id", nullable = false, length = 64)
    private String intentId;

    @Column(nullable = false, length = 32)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 16)
    private RecipientType recipientType;

    @Column(name = "recipient_id", length = 128)
    private String recipientId;

    @Column(name = "recipient_hash", nullable = false, length = 64)
    private String recipientHash;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_msg_id", length = 255)
    private String providerMsgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    /**
     * RetryWorker lease deadline (Codex 019dfa47 PR4 absorb).
     *
     * <p>Worker claim sonrası kısa süreli lease; pod crash sonrasında stale
     * lease delivery'leri yeniden claim-eligible olur (next_retry_at <= now
     * + lease expired).
     */
    @Column(name = "processing_lease_until")
    private OffsetDateTime processingLeaseUntil;

    /** Permanent failure transition timestamp (FAILED/BOUNCED). */
    @Column(name = "permanent_failure_at")
    private OffsetDateTime permanentFailureAt;

    /**
     * Worker cycle UUID (Codex 019dfa47 iter-1 P0 absorb — multi-pod claim
     * isolation). Set on RetryWorker claim, cleared post-attempt.
     */
    @Column(name = "claim_token", length = 64)
    private String claimToken;

    /**
     * Faz 23.3 PR-3 — JetSMS DLR polling state. POLL-mode provider (JetSMS)
     * ACCEPTED row'ları için bir sonraki poll zamanı; PUSH-mode (NetGSM)
     * row'larında NULL. {@code JetSmsDlrPollingWorker} {@code WHERE
     * dlr_next_poll_at <= now} ile claim eder.
     */
    @Column(name = "dlr_next_poll_at")
    private OffsetDateTime dlrNextPollAt;

    /** Faz 23.3 PR-3 — JetSMS DLR son poll zamanı (observability + max-age). */
    @Column(name = "dlr_last_poll_at")
    private OffsetDateTime dlrLastPollAt;

    /** Faz 23.3 PR-3 — JetSMS DLR toplam poll sayısı (monitoring). */
    @Column(name = "dlr_poll_count", nullable = false)
    private int dlrPollCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public RecipientType getRecipientType() { return recipientType; }
    public void setRecipientType(RecipientType recipientType) { this.recipientType = recipientType; }
    public String getRecipientId() { return recipientId; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderMsgId() { return providerMsgId; }
    public void setProviderMsgId(String providerMsgId) { this.providerMsgId = providerMsgId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public OffsetDateTime getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(OffsetDateTime lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(OffsetDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public OffsetDateTime getProcessingLeaseUntil() { return processingLeaseUntil; }
    public void setProcessingLeaseUntil(OffsetDateTime processingLeaseUntil) {
        this.processingLeaseUntil = processingLeaseUntil;
    }
    public OffsetDateTime getPermanentFailureAt() { return permanentFailureAt; }
    public void setPermanentFailureAt(OffsetDateTime permanentFailureAt) {
        this.permanentFailureAt = permanentFailureAt;
    }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }
    public OffsetDateTime getDlrNextPollAt() { return dlrNextPollAt; }
    public void setDlrNextPollAt(OffsetDateTime dlrNextPollAt) { this.dlrNextPollAt = dlrNextPollAt; }
    public OffsetDateTime getDlrLastPollAt() { return dlrLastPollAt; }
    public void setDlrLastPollAt(OffsetDateTime dlrLastPollAt) { this.dlrLastPollAt = dlrLastPollAt; }
    public int getDlrPollCount() { return dlrPollCount; }
    public void setDlrPollCount(int dlrPollCount) { this.dlrPollCount = dlrPollCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationDelivery that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode();
    }
}
