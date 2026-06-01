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
 * BE — child probe-error row for AG-039 critical services snapshot.
 * Mirrors AG-038-be probe_errors pattern: single-column @JoinColumn
 * snapshot_id + scalar tenant_id mirrored + DB-layer composite FK.
 *
 * <p>Bounded {@code code} enum + bounded optional {@code summary} ≤200 +
 * CR/LF reject enforced at the DB (V24 CHECK constraints, secondary line
 * of defense) and at the {@code ServicesPayloadPolicy} layer (strict-
 * allowlist + value-level denylist, primary gate).
 */
@Entity
@Table(name = "endpoint_services_probe_errors")
public class EndpointServicesProbeError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointServicesSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "service_name", length = 64)
    private String serviceName;

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
    public EndpointServicesSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointServicesSnapshot snapshot) { this.snapshot = snapshot; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
