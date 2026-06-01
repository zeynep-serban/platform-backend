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
 * BE — child probe-error row for AG-041 Application Control snapshot.
 * Mirrors AG-040 probe_errors pattern: single-column @JoinColumn
 * snapshot_id + scalar tenant_id mirrored + DB-layer composite FK.
 *
 * <p>Bounded {@code code} enum (8 codes; Codex 019e840e plan iter-2
 * absorb #9) + optional {@code source} (3-value lowercase enum
 * wdac|appLocker|filesystem — Codex iter-2 absorb #10) + bounded
 * optional {@code summary} ≤200 + CR/LF reject enforced at the DB
 * (V26 CHECK constraints) and at the {@code AppControlPayloadPolicy}
 * layer (strict-allowlist + value-level denylist via
 * SUMMARY_VALUE_DENYLIST_RE reuse).
 */
@Entity
@Table(name = "endpoint_app_control_probe_errors")
public class EndpointAppControlProbeError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointAppControlSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "source", length = 16)
    private String source;

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
    public EndpointAppControlSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointAppControlSnapshot snapshot) { this.snapshot = snapshot; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
