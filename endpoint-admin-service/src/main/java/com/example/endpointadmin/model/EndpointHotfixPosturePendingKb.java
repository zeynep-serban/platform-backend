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
 * BE — grand-child KB id facet of an
 * {@link EndpointHotfixPosturePending} (Faz 22.5, AG-037 ingest).
 *
 * <p>Codex 019e81fe iter-2 P1.5: kbIds are normalized as a separate child
 * table (NOT JSONB on the pending row) so fleet / compliance KB-based
 * queries can index without JSONB array contains scans. The composite FK
 * {@code (pending_id, tenant_id)} binds to the parent's
 * {@code UNIQUE(id, tenant_id)} (Codex iter-2 P1.2).
 *
 * <p>Redaction boundary: KB id only (regex {@code ^KB[0-9]{4,10}$}). No
 * raw update title / description / install metadata.
 *
 * <p>The {@code (tenant_id, kb_id)} index supports the future fleet
 * query "which devices in this tenant have KB5036899 pending?" without
 * cross-tenant scan.
 */
@Entity
@Table(name = "endpoint_hotfix_posture_pending_kbs",
        indexes = {
                @Index(name = "idx_endpoint_hotfix_post_pending_kbs_pending",
                        columnList = "pending_id,row_ordinal"),
                @Index(name = "idx_endpoint_hotfix_post_pending_kbs_tnt_kb",
                        columnList = "tenant_id,kb_id")
        })
public class EndpointHotfixPosturePendingKb {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pending_id", nullable = false, updatable = false)
    private EndpointHotfixPosturePending pending;

    /** Tenant column — mirrored from {@code pending.tenantId} at persist
     *  time. The DB-layer composite FK
     *  {@code (pending_id, tenant_id) → pending(id, tenant_id)} enforces
     *  tenant integrity. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** KB identifier ('KB' + 4..10 digit MS knowledge-base id). */
    @Column(name = "kb_id", nullable = false, length = 32)
    private String kbId;

    /** Stable replay ordinal (per pending, unique). */
    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (pending != null && tenantId == null) {
            tenantId = pending.getTenantId();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public EndpointHotfixPosturePending getPending() { return pending; }
    public void setPending(EndpointHotfixPosturePending pending) {
        this.pending = pending;
        if (pending != null) {
            this.tenantId = pending.getTenantId();
        }
    }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }

    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }

    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHotfixPosturePendingKb that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
