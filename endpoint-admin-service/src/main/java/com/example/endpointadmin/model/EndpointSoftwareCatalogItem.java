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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Approved Software Catalog item — BE-020, Faz 22.5.3.
 *
 * <p>Tenant-scoped catalog of approved software for the Endpoint Admin /
 * Endpoint-Enes deployment hat. AG-027 (install adapter) consumes
 * {@link CatalogItemStatus#APPROVED} + {@code enabled=true} rows only;
 * BE-020 itself is read/write metadata only — there is no install command,
 * no agent code, no GitOps digest impact at this milestone.
 *
 * <p>Maker-checker invariant: {@link #approvedBySubject} must not equal
 * {@link #createdBySubject}. Enforced both at the DB level
 * (CHECK {@code ck_endpoint_software_catalog_items_maker_checker} in V7)
 * and at the service layer; service-layer rejects emit the
 * {@code ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit event using the BE-014A {@code noRollbackFor} pattern so the
 * reject row survives transaction rollback.
 *
 * <p>UUID generation: {@link GenerationType#UUID} matches the existing
 * {@link EndpointDevice} pattern — app-side / Hibernate-generated, not
 * a {@code DEFAULT gen_random_uuid()} on the DB side
 * (Codex 019e6a3e iter-2 acceptance #3).
 */
@Entity
@Table(name = "endpoint_software_catalog_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_software_catalog_items_tenant_catalog_item",
                        columnNames = {"tenant_id", "catalog_item_id"})
        },
        indexes = {
                @Index(name = "idx_endpoint_software_catalog_items_tenant_status_enabled",
                        columnList = "tenant_id,status,enabled"),
                @Index(name = "idx_endpoint_software_catalog_items_tenant_provider_package",
                        columnList = "tenant_id,provider,package_id")
        })
public class EndpointSoftwareCatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "catalog_item_id", nullable = false, length = 128)
    private String catalogItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CatalogItemStatus status = CatalogItemStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private CatalogProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private CatalogSourceType sourceType;

    @Column(name = "source_name", nullable = false, length = 64)
    private String sourceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_trust", nullable = false, length = 48)
    private CatalogSourceTrust sourceTrust;

    @Column(name = "package_id", nullable = false, length = 128)
    private String packageId;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(name = "publisher", nullable = false, length = 128)
    private String publisher;

    @Enumerated(EnumType.STRING)
    @Column(name = "version_policy_type", nullable = false, length = 16)
    private CatalogVersionPolicyType versionPolicyType;

    @Column(name = "version_policy_value", length = 64)
    private String versionPolicyValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "installer_type", length = 16)
    private CatalogInstallerType installerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "silent_args_policy", length = 32)
    private CatalogSilentArgsPolicy silentArgsPolicy;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "provenance", length = 256)
    private String provenance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_rule", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detectionRule = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 8)
    private CatalogRiskTier riskTier = CatalogRiskTier.LOW;

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
        if (detectionRule == null) {
            detectionRule = new HashMap<>();
        }
    }

    @PreUpdate
    void preUpdate() {
        lastUpdatedAt = Instant.now();
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

    public String getCatalogItemId() {
        return catalogItemId;
    }

    public void setCatalogItemId(String catalogItemId) {
        this.catalogItemId = catalogItemId;
    }

    public CatalogItemStatus getStatus() {
        return status;
    }

    public void setStatus(CatalogItemStatus status) {
        this.status = status;
    }

    public CatalogProvider getProvider() {
        return provider;
    }

    public void setProvider(CatalogProvider provider) {
        this.provider = provider;
    }

    public CatalogSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(CatalogSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public CatalogSourceTrust getSourceTrust() {
        return sourceTrust;
    }

    public void setSourceTrust(CatalogSourceTrust sourceTrust) {
        this.sourceTrust = sourceTrust;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public CatalogVersionPolicyType getVersionPolicyType() {
        return versionPolicyType;
    }

    public void setVersionPolicyType(CatalogVersionPolicyType versionPolicyType) {
        this.versionPolicyType = versionPolicyType;
    }

    public String getVersionPolicyValue() {
        return versionPolicyValue;
    }

    public void setVersionPolicyValue(String versionPolicyValue) {
        this.versionPolicyValue = versionPolicyValue;
    }

    public CatalogInstallerType getInstallerType() {
        return installerType;
    }

    public void setInstallerType(CatalogInstallerType installerType) {
        this.installerType = installerType;
    }

    public CatalogSilentArgsPolicy getSilentArgsPolicy() {
        return silentArgsPolicy;
    }

    public void setSilentArgsPolicy(CatalogSilentArgsPolicy silentArgsPolicy) {
        this.silentArgsPolicy = silentArgsPolicy;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getProvenance() {
        return provenance;
    }

    public void setProvenance(String provenance) {
        this.provenance = provenance;
    }

    public Map<String, Object> getDetectionRule() {
        return detectionRule;
    }

    public void setDetectionRule(Map<String, Object> detectionRule) {
        this.detectionRule = detectionRule == null ? new HashMap<>() : detectionRule;
    }

    public CatalogRiskTier getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(CatalogRiskTier riskTier) {
        this.riskTier = riskTier;
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
        if (!(o instanceof EndpointSoftwareCatalogItem that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
