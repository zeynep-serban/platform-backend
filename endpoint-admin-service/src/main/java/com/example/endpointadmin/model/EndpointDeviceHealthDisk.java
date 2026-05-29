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
 * BE — per-fixed-disk facet of an {@link EndpointDeviceHealthSnapshot}
 * (Faz 22.5, AG-033 device-health ingest).
 *
 * <p>Redaction boundary (security invariant): a disk facet carries ONLY
 * {@code driveLetter} ({@code ^[A-Z]:$}), byte totals, the derived
 * percent, and the low-disk warning — NO volume label / serial /
 * filesystem / mount path / GUID. The DB CHECK on {@code drive_letter}
 * enforces the only legitimate disk identifier shape.
 *
 * <p>Composite {@code (snapshot_id, tenant_id)} FK enforces tenant
 * integrity at the DB layer (parity with the V13 hardware-inventory
 * disk facet): a service bug cannot persist a disk row under one tenant
 * while its snapshot lives under another. {@code ON DELETE CASCADE} on
 * the snapshot means a single DELETE on the parent removes the child
 * disks atomically.
 */
@Entity
@Table(name = "endpoint_device_health_disks",
        indexes = @Index(
                name = "idx_endpoint_device_health_disks_snapshot",
                columnList = "snapshot_id,tenant_id"))
public class EndpointDeviceHealthDisk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Single-column Hibernate association on {@code snapshot_id}. The
     * DB-layer composite FK {@code (snapshot_id, tenant_id) →
     * snapshots(id, tenant_id) ON DELETE CASCADE} is enforced by the
     * V17 migration's foreign key constraint, NOT by Hibernate (parity
     * with the V13 hardware-inventory disk pattern). Hibernate persists
     * the {@code tenant_id} column as a dedicated scalar field at
     * persist time so the DB constraint can verify the (snapshot,
     * tenant) pair.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointDeviceHealthSnapshot snapshot;

    /** Tenant column — mirrored from {@code snapshot.tenantId} at persist
     * time by {@link #onPersist()}. The DB-layer composite FK enforces
     * that {@code (snapshot_id, tenant_id)} matches the snapshot row. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Uppercase drive letter only ({@code ^[A-Z]:$}). The ONLY disk
     * identifier inside the redaction boundary. */
    @Column(name = "drive_letter", nullable = false, length = 2)
    private String driveLetter;

    @Column(name = "total_bytes")
    private Long totalBytes;

    /** freeBytesAvailableToCaller (LocalSystem-writable). */
    @Column(name = "free_bytes")
    private Long freeBytes;

    @Column(name = "free_percent")
    private Short freePercent;

    @Column(name = "low_disk_warning")
    private Boolean lowDiskWarning;

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
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public EndpointDeviceHealthSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(EndpointDeviceHealthSnapshot snapshot) {
        this.snapshot = snapshot;
        if (snapshot != null) {
            this.tenantId = snapshot.getTenantId();
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getDriveLetter() {
        return driveLetter;
    }

    public void setDriveLetter(String driveLetter) {
        this.driveLetter = driveLetter;
    }

    public Long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(Long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public Long getFreeBytes() {
        return freeBytes;
    }

    public void setFreeBytes(Long freeBytes) {
        this.freeBytes = freeBytes;
    }

    public Short getFreePercent() {
        return freePercent;
    }

    public void setFreePercent(Short freePercent) {
        this.freePercent = freePercent;
    }

    public Boolean getLowDiskWarning() {
        return lowDiskWarning;
    }

    public void setLowDiskWarning(Boolean lowDiskWarning) {
        this.lowDiskWarning = lowDiskWarning;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointDeviceHealthDisk that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
