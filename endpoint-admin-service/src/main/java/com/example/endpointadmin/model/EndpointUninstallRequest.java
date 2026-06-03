package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * AG-028 Phase 1 — JPA entity for the in-flight uninstall request state
 * machine (Faz 22.5.6).
 *
 * <p>Counterpart to {@link EndpointInstallAudit} on the destructive side.
 * Lifecycle managed by {@code EndpointUninstallService} (Phase 1):
 *
 * <pre>
 *   PENDING_APPROVAL → APPROVED → QUEUED → CLAIMED → RUNNING → TERMINAL
 *   PENDING_APPROVAL                                          → TERMINAL  (reject path)
 * </pre>
 *
 * <p>DB invariants enforced in V32:
 * <ul>
 *   <li>{@code ck_endpoint_uninstall_state}: closed state allowlist.</li>
 *   <li>{@code uq_endpoint_uninstall_requests_id_tenant}: composite tenant unique.</li>
 *   <li>{@code fk_endpoint_uninstall_requests_device}: composite (device_id, tenant_id) FK.</li>
 *   <li>{@code fk_endpoint_uninstall_requests_catalog}: composite (catalog_item_id, tenant_id) FK.</li>
 *   <li>{@code fk_endpoint_uninstall_requests_command}: deferred composite (command_id, tenant_id) FK.</li>
 *   <li>{@code uq_endpoint_uninstall_one_inflight}: partial unique on (tenant, device, catalog) where state != TERMINAL.</li>
 *   <li>{@code uq_endpoint_uninstall_idempotency}: partial unique on (tenant, idempotency_key) where not null.</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8d81-3d87-78f2-ba17-9a8981c5eb16} iter-2 AGREE.
 */
@Entity
@Table(name = "endpoint_uninstall_requests",
        indexes = {
                @Index(name = "idx_endpoint_uninstall_requests_tenant_state",
                        columnList = "tenant_id,state"),
                @Index(name = "idx_endpoint_uninstall_requests_device_created",
                        columnList = "tenant_id,device_id,created_at DESC")
        })
public class EndpointUninstallRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Column(name = "command_id")
    private UUID commandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private UninstallRequestState state;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "state_updated_at", nullable = false)
    private Instant stateUpdatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (stateUpdatedAt == null) {
            stateUpdatedAt = now;
        }
        if (state == null) {
            state = UninstallRequestState.PENDING_APPROVAL;
        }
    }

    @PreUpdate
    void preUpdate() {
        stateUpdatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointUninstallRequest that = (EndpointUninstallRequest) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getCatalogItemId() { return catalogItemId; }
    public void setCatalogItemId(UUID catalogItemId) { this.catalogItemId = catalogItemId; }
    public UUID getCommandId() { return commandId; }
    public void setCommandId(UUID commandId) { this.commandId = commandId; }
    public UninstallRequestState getState() { return state; }
    public void setState(UninstallRequestState state) { this.state = state; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStateUpdatedAt() { return stateUpdatedAt; }
    public void setStateUpdatedAt(Instant stateUpdatedAt) { this.stateUpdatedAt = stateUpdatedAt; }
}
