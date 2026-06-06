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
 * Approved package bundle definition — BE-029 (Faz 22.5.8).
 *
 * <p>A bundle is a named set of already-approved catalog items. It is a
 * control-plane primitive for later rollout policies (rings/windows/throttle),
 * not an install dispatcher. The future dispatch path must still resolve every
 * bundle item back through the approved catalog contract.
 */
@Entity
@Table(name = "endpoint_software_bundles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_software_bundles_org_bundle",
                        columnNames = {"org_id", "bundle_id"})
        },
        indexes = {
                @Index(name = "idx_endpoint_software_bundles_tenant_status_enabled",
                        columnList = "tenant_id,status,enabled")
        })
public class EndpointSoftwareBundle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "bundle_id", nullable = false, length = 128)
    private String bundleId;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SoftwareBundleStatus status = SoftwareBundleStatus.DRAFT;

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

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public SoftwareBundleStatus getStatus() {
        return status;
    }

    public void setStatus(SoftwareBundleStatus status) {
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
        if (!(o instanceof EndpointSoftwareBundle that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
