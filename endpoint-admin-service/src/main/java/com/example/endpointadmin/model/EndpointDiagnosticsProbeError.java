package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * BE — child probe-error row for AG-038 agent self-diagnostics snapshot.
 * Mirrors the AG-037 {@code EndpointHotfixPostureInstalled} child entity
 * pattern EXACTLY: single-column Hibernate association on
 * {@code snapshot_id}, scalar {@code tenant_id} mirrored at persist time
 * from the parent snapshot, DB-layer composite FK
 * {@code (snapshot_id, tenant_id) → snapshots(id, tenant_id) ON DELETE
 * CASCADE} enforced by the V23 migration (NOT by Hibernate). Bounded
 * {@code code} regex + bounded {@code summary} ≤200 chars + CR/LF
 * REJECT are enforced both at the DB (V23 CHECK) and at the policy.
 */
@Entity
@Table(name = "endpoint_diagnostics_probe_errors")
public class EndpointDiagnosticsProbeError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Single-column Hibernate association on {@code snapshot_id}. The
     * DB-layer composite FK {@code (snapshot_id, tenant_id) →
     * snapshots(id, tenant_id) ON DELETE CASCADE} is enforced by the
     * V23 migration foreign key constraint, NOT by Hibernate (parity
     * with the V22 hotfix-posture facet pattern). Hibernate persists
     * the {@code tenant_id} column as a dedicated scalar at persist
     * time so the DB constraint can verify the (snapshot, tenant) pair.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointDiagnosticsSnapshot snapshot;

    /** Tenant column — mirrored from {@code snapshot.tenantId} at persist
     *  time. The DB-layer composite FK enforces parity. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "summary", length = 200)
    private String summary;

    @PrePersist
    void onPersist() {
        if (tenantId == null && snapshot != null) {
            tenantId = snapshot.getTenantId();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public EndpointDiagnosticsSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointDiagnosticsSnapshot snapshot) { this.snapshot = snapshot; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
