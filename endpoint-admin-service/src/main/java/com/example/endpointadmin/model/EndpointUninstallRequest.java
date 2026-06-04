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
 * <p>DB invariants (created in V32; FKs + business partial-uniques flipped to
 * org-keyed in Faz 21.1 C4 V49 — {@code org_id = tenant_id}, reads tenant-keyed,
 * {@code tenant_id} + {@code uq_..._id_tenant} retained until A6):
 * <ul>
 *   <li>{@code ck_endpoint_uninstall_state}: closed state allowlist.</li>
 *   <li>{@code endpoint_uninstall_requests_id_org_id_key}: composite (id, org_id) unique (V49 FK target; V32 {@code uq_..._id_tenant} retained for A6).</li>
 *   <li>{@code uninstall_req_device_org_fk}: composite (device_id, org_id) FK.</li>
 *   <li>{@code uninstall_req_catalog_org_fk}: composite (catalog_item_id, org_id) FK.</li>
 *   <li>{@code uninstall_req_command_org_fk}: deferred composite (command_id, org_id) FK (DEFERRABLE INITIALLY DEFERRED).</li>
 *   <li>{@code uq_endpoint_uninstall_one_inflight}: partial unique on (org_id, device, catalog) where state != TERMINAL.</li>
 *   <li>{@code uq_endpoint_uninstall_idempotency}: partial unique on (org_id, idempotency_key) where not null.</li>
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

    /**
     * Faz 21.1 C4 V49 org_id compat field (Option A — same pattern as
     * EndpointCommand / EndpointSoftwareCatalogItem). Mapped to the V49
     * {@code org_id} column; nullable in JPA (the VALIDATED CHECK
     * {@code org_id IS NOT NULL} + {@code org_id = tenant_id} is the live
     * enforcement surface, column-level SET NOT NULL deferred to A6).
     * Canonicalized to {@code tenantId} in {@link #prePersist()} /
     * {@link #preUpdate()} so the org-keyed business arbiters (idempotency +
     * one-inflight partial uniques) hold on the H2 create-drop test path too.
     * Reads stay tenant-keyed (A5 deferred); {@link #getEffectiveOrgId()}
     * resolves legacy {@code orgId == null} rows.
     */
    @Column(name = "org_id")
    private UUID orgId;

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
        canonicalizeOrgId();
    }

    @PreUpdate
    void preUpdate() {
        stateUpdatedAt = Instant.now();
        canonicalizeOrgId();
    }

    /**
     * Faz 21.1 C4 V49 canonical org_id write: mirror the V49 DB
     * {@code endpoint_org_id_compat_fill()} BEFORE-trigger at the JPA layer so
     * the org-keyed business arbiters hold even on the H2 create-drop test path
     * (no DB trigger). org_id = tenant_id (V49 match CHECK invariant); never
     * overwrites an explicitly-set value.
     */
    private void canonicalizeOrgId() {
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
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
    /** Faz 21.1 C4 V49 org_id accessor (may be null on legacy rows; use {@link #getEffectiveOrgId()} for reads). */
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    /** Faz 21.1 C4 V49 effective-org accessor: orgId fallback to tenantId. */
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
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
