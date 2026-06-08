package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only audit of device DECOMMISSION / REACTIVATE lifecycle transitions
 * (V56). Mirrors the {@code EndpointUninstallAudit} append-only pattern:
 *
 * <ul>
 *   <li>Append-only via the V56 trigger
 *       {@code trg_endpoint_device_lifecycle_audit_append_only} — any
 *       UPDATE/DELETE raises a PSQL exception, so there is no {@code @PreUpdate}
 *       and {@code orgId} is canonicalized in {@link #prePersist()} only.</li>
 *   <li>The hash-chained BE-016 audit event is emitted in the service layer
 *       (existing {@code endpoint_audit_events} chain) for forensic integrity;
 *       this table is the structured lifecycle projection (who/when/why +
 *       cascade counts) and does NOT carry its own row-hash chain.</li>
 *   <li>org-composite: {@code (device_id, org_id)} FK to
 *       {@code endpoint_devices(id, org_id)}; {@code org_id} filled to
 *       {@code tenant_id} by the V29 compat trigger + canonicalized here.</li>
 * </ul>
 *
 * <p>KVKK: a decommission is reversible (reactivate); the device + its data are
 * retained — this is "deactivate, not delete".
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019ea789-3060-7fd2-a6d6-feceefb31272} iter-2 AGREE.
 */
@Entity
@Table(name = "endpoint_device_lifecycle_audit",
        indexes = {
                @Index(name = "ix_endpoint_device_lifecycle_audit_tenant_device_created",
                        columnList = "tenant_id,device_id,created_at DESC")
        })
public class EndpointDeviceLifecycleAudit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * org_id compat field (Faz 21.1 Option A). Mapped to the V56 {@code org_id}
     * column; nullable in JPA (the DB CHECK + compat trigger are the live
     * enforcement). Canonicalized to {@code tenantId} in {@link #prePersist()}
     * only — this table is append-only.
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private DeviceLifecycleAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 32)
    private DeviceStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private DeviceStatus toStatus;

    @Column(name = "actor_subject", nullable = false)
    private String actorSubject;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "cancelled_commands", nullable = false)
    private int cancelledCommands;

    @Column(name = "revoked_tokens", nullable = false)
    private int revokedTokens;

    @Column(name = "finalized_uninstalls", nullable = false)
    private int finalizedUninstalls;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointDeviceLifecycleAudit that = (EndpointDeviceLifecycleAudit) o;
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
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    /** Effective-org accessor: orgId fallback to tenantId for legacy rows. */
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public DeviceLifecycleAction getAction() { return action; }
    public void setAction(DeviceLifecycleAction action) { this.action = action; }
    public DeviceStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(DeviceStatus fromStatus) { this.fromStatus = fromStatus; }
    public DeviceStatus getToStatus() { return toStatus; }
    public void setToStatus(DeviceStatus toStatus) { this.toStatus = toStatus; }
    public String getActorSubject() { return actorSubject; }
    public void setActorSubject(String actorSubject) { this.actorSubject = actorSubject; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public int getCancelledCommands() { return cancelledCommands; }
    public void setCancelledCommands(int cancelledCommands) { this.cancelledCommands = cancelledCommands; }
    public int getRevokedTokens() { return revokedTokens; }
    public void setRevokedTokens(int revokedTokens) { this.revokedTokens = revokedTokens; }
    public int getFinalizedUninstalls() { return finalizedUninstalls; }
    public void setFinalizedUninstalls(int finalizedUninstalls) { this.finalizedUninstalls = finalizedUninstalls; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
