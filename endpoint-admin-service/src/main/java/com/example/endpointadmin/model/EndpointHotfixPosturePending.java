package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * BE — per-pending-update facet of an
 * {@link EndpointHotfixPostureSnapshot} (Faz 22.5, AG-037 ingest).
 *
 * <p>Redaction boundary (security invariant — DO NOT widen): a pending
 * facet carries ONLY {@code primaryCategory} (enum:
 * SECURITY|DEFINITION|CRITICAL|IMPORTANT|DRIVER|UPDATE_ROLLUP|FEATURE_PACK|
 * SERVICE_PACK|OPTIONAL|TOOLS|UNCATEGORIZED) and {@code severity} (MSRC
 * enum: CRITICAL|IMPORTANT|MODERATE|LOW|UNSPECIFIED). The associated
 * {@code kbIds} live in a separate composite-FK normalized child table
 * ({@link EndpointHotfixPosturePendingKb} — Codex 019e81fe iter-2 P1.5 so
 * KB-bazlı fleet/compliance queries can index without JSONB array scans).
 * NO raw update title is on the wire in v1 (operator-visible noise + leak
 * vector).
 *
 * <p>Composite {@code (snapshot_id, tenant_id)} FK enforces tenant
 * integrity at the DB layer; {@code ON DELETE CASCADE} on the snapshot
 * means the cascade reaches both this row AND the grand-child
 * {@code pending_kbs} via the {@code (pending_id, tenant_id)} FK chain.
 *
 * <p>{@code (id, tenant_id)} UNIQUE (Codex 019e81fe iter-2 P1.2) is the
 * parent target the grand-child {@code pending_kbs} FK requires.
 */
@Entity
@Table(name = "endpoint_hotfix_posture_pending",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_endpoint_hotfix_post_pending_id_tnt",
                columnNames = {"id", "tenant_id"}),
        indexes = @Index(
                name = "idx_endpoint_hotfix_post_pending_snap",
                columnList = "snapshot_id,row_ordinal"))
public class EndpointHotfixPosturePending {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointHotfixPostureSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "primary_category", nullable = false, length = 16)
    private String primaryCategory;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Grand-child KB ids (LAZY). Composite FK
     *  {@code (pending_id, tenant_id)} requires the parent
     *  {@code UNIQUE(id, tenant_id)} above. {@code @OrderBy} pins the
     *  deterministic replay order to the {@code rowOrdinal} the agent
     *  emitted (Codex 019e822b P2 follow-up). */
    @OneToMany(mappedBy = "pending", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointHotfixPosturePendingKb> kbs = new ArrayList<>();

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

    public String getPrimaryCategory() { return primaryCategory; }
    public void setPrimaryCategory(String primaryCategory) { this.primaryCategory = primaryCategory; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }

    public Instant getCreatedAt() { return createdAt; }

    public List<EndpointHotfixPosturePendingKb> getKbs() { return kbs; }
    public void setKbs(List<EndpointHotfixPosturePendingKb> kbs) {
        this.kbs = kbs == null ? new ArrayList<>() : kbs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHotfixPosturePending that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
