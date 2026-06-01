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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BE — pendingByCategory rollup facet of an
 * {@link EndpointHotfixPostureSnapshot} (Faz 22.5, AG-037 ingest).
 *
 * <p>Codex 019e81fe iter-2 P1.1: the wire contract's
 * {@code pendingByCategory} carries the FULL pre-truncation category
 * distribution even when the per-item {@code pendingUpdates} list is
 * capped at 20. Persisting this rollup as a normalized child table lets
 * the snapshot answer "how many SECURITY updates are pending, in total?"
 * without parsing the JSONB {@code redactedPayload}.
 *
 * <p>{@code UNIQUE(snapshot_id, category)} enforces one row per category
 * per snapshot (no double-counting); {@code UNIQUE(snapshot_id,
 * row_ordinal)} preserves the agent's deterministic emit order for
 * stable replay (P2 from Codex 019e81fe iter-3).
 *
 * <p>{@code cnt} (intentionally short — Postgres reserves {@code count})
 * is the integer total for the category.
 */
@Entity
@Table(name = "endpoint_hotfix_posture_pending_categories",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_hotfix_post_pending_cats_snap_category",
                        columnNames = {"snapshot_id", "category"}),
                @UniqueConstraint(
                        name = "uq_endpoint_hotfix_post_pending_cats_snap_ordinal",
                        columnNames = {"snapshot_id", "row_ordinal"})
        },
        indexes = @Index(
                name = "idx_endpoint_hotfix_post_pending_cats_snap",
                columnList = "snapshot_id,row_ordinal"))
public class EndpointHotfixPosturePendingCategoryCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointHotfixPostureSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Category enum (parity with pending.primaryCategory). */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    /** Pre-truncation count (>= 0). Sum across all rows for the snapshot
     *  equals {@code pendingTotalCount} (HotfixPosturePayloadPolicy
     *  invariant). */
    @Column(name = "cnt", nullable = false)
    private Integer cnt;

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

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getCnt() { return cnt; }
    public void setCnt(Integer cnt) { this.cnt = cnt; }

    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHotfixPosturePendingCategoryCount that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
