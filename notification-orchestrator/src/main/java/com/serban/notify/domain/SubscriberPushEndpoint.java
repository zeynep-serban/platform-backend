package com.serban.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * WebPush subscriber endpoint (Faz 23.7 M7 T4.2 PR-W1).
 *
 * <p>Browser PushSubscription registry (RFC 8030 + RFC 8291). Her
 * subscriber 1+ browser/cihaz için ayrı endpoint row.
 *
 * <p>Mobile (FCM/APNS) Faz 22.2 follow-up scope; bu entity BROWSER-only.
 *
 * <p>V19 migration: {@code notify.subscriber_push_endpoint}.
 */
@Entity
@Table(schema = "notify", name = "subscriber_push_endpoint")
public class SubscriberPushEndpoint {

    @Id
    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "subscriber_id", nullable = false, length = 128)
    private String subscriberId;

    @Column(name = "endpoint_url", nullable = false, length = 2048)
    private String endpointUrl;

    @Column(name = "p256dh_key", nullable = false, length = 512)
    private String p256dhKey;

    @Column(name = "auth_secret", nullable = false, length = 256)
    private String authSecret;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "platform_hint", length = 64)
    private String platformHint;

    @Column(name = "expiration_time")
    private OffsetDateTime expirationTime;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_failure_at")
    private OffsetDateTime lastFailureAt;

    @Column(name = "last_failure_reason", length = 128)
    private String lastFailureReason;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (endpointId == null) {
            endpointId = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters / Setters

    public UUID getEndpointId() { return endpointId; }
    public void setEndpointId(UUID endpointId) { this.endpointId = endpointId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public String getP256dhKey() { return p256dhKey; }
    public void setP256dhKey(String p256dhKey) { this.p256dhKey = p256dhKey; }

    public String getAuthSecret() { return authSecret; }
    public void setAuthSecret(String authSecret) { this.authSecret = authSecret; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getPlatformHint() { return platformHint; }
    public void setPlatformHint(String platformHint) { this.platformHint = platformHint; }

    public OffsetDateTime getExpirationTime() { return expirationTime; }
    public void setExpirationTime(OffsetDateTime expirationTime) { this.expirationTime = expirationTime; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public OffsetDateTime getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(OffsetDateTime lastFailureAt) { this.lastFailureAt = lastFailureAt; }

    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
