package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
 * Ordered member of an approved software bundle (BE-029).
 *
 * <p>Every member references an existing catalog row. The service only admits
 * {@code APPROVED + enabled=true} catalog items; the database additionally
 * keeps the reference org-scoped through {@code (catalog_item_id, org_id)}.
 */
@Entity
@Table(name = "endpoint_software_bundle_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_software_bundle_items_bundle_order",
                        columnNames = {"bundle_id", "item_order"}),
                @UniqueConstraint(
                        name = "uq_endpoint_software_bundle_items_bundle_catalog",
                        columnNames = {"bundle_id", "catalog_item_id"})
        },
        indexes = {
                @Index(name = "idx_endpoint_software_bundle_items_tenant_bundle",
                        columnList = "tenant_id,bundle_id,item_order")
        })
public class EndpointSoftwareBundleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "bundle_id", nullable = false)
    private UUID bundleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "bundle_id", referencedColumnName = "id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "org_id", referencedColumnName = "org_id",
                    insertable = false, updatable = false)
    },
            foreignKey = @ForeignKey(
                    name = "fk_endpoint_software_bundle_items_bundle"))
    private EndpointSoftwareBundle bundle;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "catalog_item_id", referencedColumnName = "id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "org_id", referencedColumnName = "org_id",
                    insertable = false, updatable = false)
    },
            foreignKey = @ForeignKey(
                    name = "fk_endpoint_software_bundle_items_catalog"))
    private EndpointSoftwareCatalogItem catalogItem;

    @Column(name = "item_order", nullable = false)
    private int itemOrder;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public UUID getBundleId() {
        return bundleId;
    }

    public void setBundleId(UUID bundleId) {
        this.bundleId = bundleId;
    }

    public EndpointSoftwareBundle getBundle() {
        return bundle;
    }

    public void setBundle(EndpointSoftwareBundle bundle) {
        this.bundle = bundle;
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

    public void setCatalogItem(EndpointSoftwareCatalogItem catalogItem) {
        this.catalogItem = catalogItem;
    }

    public int getItemOrder() {
        return itemOrder;
    }

    public void setItemOrder(int itemOrder) {
        this.itemOrder = itemOrder;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public Instant getCreatedAt() {
        return createdAt;
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
        if (!(o instanceof EndpointSoftwareBundleItem that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
