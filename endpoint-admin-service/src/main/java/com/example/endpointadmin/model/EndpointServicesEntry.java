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
 * BE — child service-entry row for AG-039 critical services inventory.
 * Single-column @JoinColumn(snapshot_id) + scalar tenant_id mirrored at
 * @PrePersist (AG-038-be probe_error pattern parity); DB-layer composite
 * FK (snapshot_id, tenant_id) → snapshots(id, tenant_id) ON DELETE CASCADE.
 *
 * <p>Allowlist enforcement: name MUST be one of WinDefend, wuauserv,
 * BITS, EventLog, EndpointAgent, MpsSvc (DB CHECK + policy CHECK).
 *
 * <p>State + StartupMode are bounded enum strings; full enum surface
 * pinned in DB CHECK and policy.
 */
@Entity
@Table(name = "endpoint_services_entries")
public class EndpointServicesEntry {

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

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "present", nullable = false)
    private Boolean present;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "startup_mode", nullable = false, length = 16)
    private String startupMode;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getPresent() { return present; }
    public void setPresent(Boolean present) { this.present = present; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStartupMode() { return startupMode; }
    public void setStartupMode(String startupMode) { this.startupMode = startupMode; }
}
