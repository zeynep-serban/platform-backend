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
 * BE — per-installed-hotfix facet of an
 * {@link EndpointHotfixPostureSnapshot} (Faz 22.5, AG-037 ingest).
 *
 * <p>Redaction boundary (security invariant — DO NOT widen): a hotfix
 * facet carries ONLY {@code kbId} (KB regex {@code ^KB[0-9]{4,10}$}),
 * the optional {@code installedOn} timestamp (wire allows null), and an
 * optional {@code description} (operator label only). NO update title /
 * install client / install account / install command / supersedence
 * chain / product code is on the wire. The DB CHECK on {@code kb_id}
 * enforces the only legitimate hotfix identifier shape; no column exists
 * for any forbidden field.
 *
 * <p>Composite {@code (snapshot_id, tenant_id)} FK enforces tenant
 * integrity at the DB layer (parity with V20 outdated-software's
 * per-package facet): a service bug cannot persist a hotfix row under one
 * tenant while its snapshot lives under another. {@code ON DELETE CASCADE}
 * on the snapshot means a single DELETE on the parent removes the
 * children atomically.
 *
 * <p>{@code rowOrdinal} preserves the agent's deterministic emit order so
 * a snapshot round-trip (persist → re-fetch) reloads the hotfixes in the
 * same order they were collected.
 */
@Entity
@Table(name = "endpoint_hotfix_posture_installed",
        indexes = @Index(
                name = "idx_endpoint_hotfix_post_installed_snap",
                columnList = "snapshot_id,row_ordinal"))
public class EndpointHotfixPostureInstalled {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Single-column Hibernate association on {@code snapshot_id}. The
     * DB-layer composite FK {@code (snapshot_id, tenant_id) →
     * snapshots(id, tenant_id) ON DELETE CASCADE} is enforced by the
     * V22 migration's foreign key constraint, NOT by Hibernate (parity
     * with the V20 outdated-software facet pattern). Hibernate persists
     * the {@code tenant_id} column as a dedicated scalar at persist
     * time so the DB constraint can verify the (snapshot, tenant) pair.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointHotfixPostureSnapshot snapshot;

    /** Tenant column — mirrored from {@code snapshot.tenantId} at persist
     *  time by {@link #onPersist()}. The DB-layer composite FK enforces
     *  that {@code (snapshot_id, tenant_id)} matches the snapshot row. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** KB identifier ('KB' + 4..10 digit MS knowledge-base id). The ONLY
     *  per-hotfix correlation key on the wire. */
    @Column(name = "kb_id", nullable = false, length = 32)
    private String kbId;

    /** Wire contract permits null (Get-HotFix legitimately returns
     *  legacy patches without a parseable installed-on date). */
    @Column(name = "installed_on")
    private Instant installedOn;

    /** Operator-friendly description from WUA or {@code Get-HotFix}. */
    @Column(name = "description", length = 512)
    private String description;

    /** Stable replay ordinal (per snapshot, unique). */
    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public EndpointHotfixPostureSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointHotfixPostureSnapshot snapshot) {
        this.snapshot = snapshot;
        if (snapshot != null) {
            this.tenantId = snapshot.getTenantId();
        }
    }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }

    public Instant getInstalledOn() { return installedOn; }
    public void setInstalledOn(Instant installedOn) { this.installedOn = installedOn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHotfixPostureInstalled that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
