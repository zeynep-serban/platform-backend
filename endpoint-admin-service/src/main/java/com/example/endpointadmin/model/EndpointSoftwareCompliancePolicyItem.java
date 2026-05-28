package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-tenant, per-catalog-item compliance enforcement policy — BE-023
 * (Faz 22.5).
 *
 * <p>Codex 019e6bbf iter-1 decision: enforcement intent ({@link
 * ComplianceEnforcementMode#REQUIRED REQUIRED} / {@link
 * ComplianceEnforcementMode#ALLOWED ALLOWED} / {@link
 * ComplianceEnforcementMode#FORBIDDEN FORBIDDEN}) lives in its own
 * tenant-scoped table so the catalog stays a pure metadata record and
 * a future device-group / OU-level policy layer can extend this table
 * without touching catalog semantics.
 *
 * <p>The relationship to the catalog is enforced at the database layer
 * via the composite foreign key {@code (catalog_item_id, tenant_id) ->
 * endpoint_software_catalog_items (id, tenant_id)} (Codex 019e6bbf
 * iter-3 critical_finding #2). This makes a cross-tenant catalog
 * reference physically impossible — the DB rejects the INSERT/UPDATE
 * before any service code runs.
 *
 * <p>UUID generation follows the established
 * {@link EndpointSoftwareCatalogItem} pattern:
 * {@link GenerationType#UUID Hibernate-generated}, not
 * {@code DEFAULT gen_random_uuid()} on the DB side.
 */
@Entity
@Table(name = "endpoint_software_compliance_policy_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_software_compliance_policy_items_tenant_catalog",
                        columnNames = {"tenant_id", "catalog_item_id"})
        },
        indexes = {
                @Index(name = "idx_endpoint_software_compliance_policy_items_tenant_enabled_mode",
                        columnList = "tenant_id,enabled,enforcement_mode")
        })
public class EndpointSoftwareCompliancePolicyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Composite-FK link to {@link EndpointSoftwareCatalogItem}:
     * {@code (catalog_item_id, tenant_id) -> endpoint_software_catalog_items
     * (id, tenant_id)}. Both column pairs are
     * {@code insertable=false, updatable=false} on the relationship to
     * defer ownership to the scalar columns above; mutation goes
     * through the scalar getters/setters which keeps the entity and
     * the SQL parameter binding in sync.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "catalog_item_id", referencedColumnName = "id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id",
                    insertable = false, updatable = false)
    },
            foreignKey = @jakarta.persistence.ForeignKey(
                    name = "fk_endpoint_software_compliance_policy_items_catalog"))
    private EndpointSoftwareCatalogItem catalogItem;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_mode", nullable = false, length = 16)
    private ComplianceEnforcementMode enforcementMode = ComplianceEnforcementMode.ALLOWED;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

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

    public UUID getCatalogItemId() {
        return catalogItemId;
    }

    public void setCatalogItemId(UUID catalogItemId) {
        this.catalogItemId = catalogItemId;
    }

    public EndpointSoftwareCatalogItem getCatalogItem() {
        return catalogItem;
    }

    public ComplianceEnforcementMode getEnforcementMode() {
        return enforcementMode;
    }

    public void setEnforcementMode(ComplianceEnforcementMode enforcementMode) {
        this.enforcementMode = enforcementMode;
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

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointSoftwareCompliancePolicyItem that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
