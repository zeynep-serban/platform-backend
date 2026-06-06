package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-031 — signed agent self-update release catalog.
 *
 * <p>This is a control-plane catalog of trusted agent release metadata. It does
 * not create endpoint commands, does not accept raw device-targeted URLs and
 * does not perform rollout. Future UPDATE_AGENT dispatch must resolve through
 * APPROVED+enabled rows here and the agent must still enforce local signature
 * and hash verification.
 */
@Entity
@Table(name = "endpoint_agent_update_releases",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_agent_update_releases_org_release",
                        columnNames = {"org_id", "release_id"})
        },
        indexes = {
                @Index(name = "idx_endpoint_agent_update_releases_tenant_status_enabled",
                        columnList = "tenant_id,status,enabled"),
                @Index(name = "idx_endpoint_agent_update_releases_tenant_channel",
                        columnList = "tenant_id,channel,status")
        })
public class EndpointAgentUpdateRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private AgentUpdateChannel channel = AgentUpdateChannel.STAGING;

    @Column(name = "target_version", nullable = false, length = 64)
    private String targetVersion;

    @Column(name = "binary_url", nullable = false, length = 2048)
    private String binaryUrl;

    @Column(name = "manifest_url", length = 2048)
    private String manifestUrl;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "sha512", length = 128)
    private String sha512;

    @Column(name = "signer_thumbprint", nullable = false, length = 64)
    private String signerThumbprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "signing_tier", nullable = false, length = 32)
    private AgentUpdateSigningTier signingTier =
            AgentUpdateSigningTier.LAB_ONLY_EVIDENCE;

    @Column(name = "max_bytes", nullable = false)
    private long maxBytes;

    @Column(name = "release_notes", length = 2048)
    private String releaseNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AgentUpdateReleaseStatus status = AgentUpdateReleaseStatus.DRAFT;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Column(name = "approved_by_subject", length = 255)
    private String approvedBySubject;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "revoked_by_subject", length = 255)
    private String revokedBySubject;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 512)
    private String revocationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastUpdatedAt == null) {
            lastUpdatedAt = now;
        }
        canonicalizeOrgId();
    }

    @PreUpdate
    void preUpdate() {
        lastUpdatedAt = Instant.now();
        canonicalizeOrgId();
    }

    private void canonicalizeOrgId() {
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(String releaseId) {
        this.releaseId = releaseId;
    }

    public AgentUpdateChannel getChannel() {
        return channel;
    }

    public void setChannel(AgentUpdateChannel channel) {
        this.channel = channel;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getBinaryUrl() {
        return binaryUrl;
    }

    public void setBinaryUrl(String binaryUrl) {
        this.binaryUrl = binaryUrl;
    }

    public String getManifestUrl() {
        return manifestUrl;
    }

    public void setManifestUrl(String manifestUrl) {
        this.manifestUrl = manifestUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getSha512() {
        return sha512;
    }

    public void setSha512(String sha512) {
        this.sha512 = sha512;
    }

    public String getSignerThumbprint() {
        return signerThumbprint;
    }

    public void setSignerThumbprint(String signerThumbprint) {
        this.signerThumbprint = signerThumbprint;
    }

    public AgentUpdateSigningTier getSigningTier() {
        return signingTier;
    }

    public void setSigningTier(AgentUpdateSigningTier signingTier) {
        this.signingTier = signingTier;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public void setReleaseNotes(String releaseNotes) {
        this.releaseNotes = releaseNotes;
    }

    public AgentUpdateReleaseStatus getStatus() {
        return status;
    }

    public void setStatus(AgentUpdateReleaseStatus status) {
        this.status = status;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    public void setCreatedBySubject(String createdBySubject) {
        this.createdBySubject = createdBySubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLastUpdatedBySubject() {
        return lastUpdatedBySubject;
    }

    public void setLastUpdatedBySubject(String lastUpdatedBySubject) {
        this.lastUpdatedBySubject = lastUpdatedBySubject;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public String getApprovedBySubject() {
        return approvedBySubject;
    }

    public void setApprovedBySubject(String approvedBySubject) {
        this.approvedBySubject = approvedBySubject;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRevokedBySubject() {
        return revokedBySubject;
    }

    public void setRevokedBySubject(String revokedBySubject) {
        this.revokedBySubject = revokedBySubject;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointAgentUpdateRelease that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
