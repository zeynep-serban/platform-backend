package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BE — per-upgradeable-package facet of an
 * {@link EndpointOutdatedSoftwareSnapshot} (Faz 22.5, AG-036 ingest).
 *
 * <p>Redaction boundary (security invariant): a package facet carries ONLY
 * {@code packageId} (winget id, no whitespace), {@code installedVersion},
 * and {@code availableVersion} — NO display name / publisher / install
 * location / license / download URL. The DB CHECK on {@code package_id}
 * ({@code ^\S+$}) enforces the only legitimate package identifier shape (a
 * display-name leak would contain whitespace and be rejected). No column
 * exists for any forbidden field.
 *
 * <p>Composite {@code (snapshot_id, tenant_id)} FK enforces tenant
 * integrity at the DB layer (parity with the V13/V17 disk facet): a
 * service bug cannot persist a package row under one tenant while its
 * snapshot lives under another. {@code ON DELETE CASCADE} on the snapshot
 * means a single DELETE on the parent removes the child packages
 * atomically.
 */
@Entity
@Table(name = "endpoint_outdated_software_packages",
        indexes = @Index(
                name = "idx_endpoint_outdated_software_packages_snapshot",
                columnList = "snapshot_id,tenant_id"))
public class EndpointOutdatedSoftwarePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Single-column Hibernate association on {@code snapshot_id}. The
     * DB-layer composite FK {@code (snapshot_id, tenant_id) →
     * snapshots(id, tenant_id) ON DELETE CASCADE} is enforced by the
     * V20 migration's foreign key constraint, NOT by Hibernate (parity
     * with the V13/V17 disk pattern). Hibernate persists the
     * {@code tenant_id} column as a dedicated scalar field at persist time
     * so the DB constraint can verify the (snapshot, tenant) pair.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointOutdatedSoftwareSnapshot snapshot;

    /** Tenant column — mirrored from {@code snapshot.tenantId} at persist
     * time by {@link #onPersist()}. The DB-layer composite FK enforces
     * that {@code (snapshot_id, tenant_id)} matches the snapshot row. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Faz 21.1 PR2b-i org_id compat field (Codex 019e8cac Option A).
     * Mirrors {@link #tenantId} updatable=false constraint — once written,
     * the parent snapshot's tenant scope is immutable.
     */
    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    /** Stable winget package id ({@code ^\S+$}). The ONLY package
     * identifier inside the redaction boundary. */
    @Column(name = "package_id", nullable = false, length = 256)
    private String packageId;

    /** Currently-installed version string (public, non-PII). */
    @Column(name = "installed_version", nullable = false, length = 128)
    private String installedVersion;

    /** Available (newer) version string (public, non-PII). */
    @Column(name = "available_version", nullable = false, length = 128)
    private String availableVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (snapshot != null && tenantId == null) {
            tenantId = snapshot.getTenantId();
        }
        // Codex 019e8cac iter-1 absorb: mirror orgId from the snapshot's
        // effective org at insert time. Because the column is
        // updatable=false, this is the ONLY safe write site for orgId on
        // this child table; later setOrgId() calls during UPDATE will be
        // silently ignored by Hibernate. Falling back to tenantId via
        // getEffectiveOrgId() keeps legacy snapshots (orgId NULL on parent)
        // working until PR2b-ii canonicalizes the snapshot write path.
        if (snapshot != null && orgId == null) {
            orgId = snapshot.getEffectiveOrgId();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public EndpointOutdatedSoftwareSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(EndpointOutdatedSoftwareSnapshot snapshot) {
        this.snapshot = snapshot;
        if (snapshot != null) {
            this.tenantId = snapshot.getTenantId();
            // Codex 019e8cac iter-1 absorb: mirror orgId alongside tenantId
            // at setSnapshot time. updatable=false means setOrgId() during
            // update is a no-op; eager mirror at setSnapshot covers the
            // insert path even if onPersist mutates fewer fields.
            if (this.orgId == null) {
                this.orgId = snapshot.getEffectiveOrgId();
            }
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    /** Faz 21.1 PR2b-i org_id accessor (Codex 019e8cac Option A). */
    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    /** Faz 21.1 PR2b-i effective-org accessor: orgId fallback to tenantId. */
    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public String getAvailableVersion() {
        return availableVersion;
    }

    public void setAvailableVersion(String availableVersion) {
        this.availableVersion = availableVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointOutdatedSoftwarePackage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
