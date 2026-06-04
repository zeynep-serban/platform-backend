package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AG-028 Phase 1 — terminal-result append-only audit for completed uninstalls
 * (Faz 22.5.6).
 *
 * <p>Destructive-side counterpart to {@code EndpointInstallAudit}. Append-only
 * via the V32 trigger {@code trg_endpoint_uninstall_audit_append_only} —
 * any UPDATE/DELETE raises a PSQL exception. The hash-chain {@code BE-016}
 * audit event {@code ENDPOINT_UNINSTALL_RESULT_RECORDED} is emitted in the
 * service layer (existing {@code endpoint_audit_events} chain) for forensic
 * integrity; this table does NOT have its own row-hash chain.
 *
 * <p>DB invariants enforced in V32:
 * <ul>
 *   <li>{@code ck_endpoint_uninstall_audit_result_status}: closed
 *       {@link UninstallResultStatus} allowlist.</li>
 *   <li>{@code ck_endpoint_uninstall_audit_verification}: closed
 *       {@link UninstallVerification} allowlist.</li>
 *   <li>{@code ck_endpoint_uninstall_audit_redacted_payload_shape}:
 *       {@code jsonb_typeof(redacted_payload) = 'object'}.</li>
 *   <li>{@code ck_endpoint_uninstall_audit_detection_evidence_shape}:
 *       {@code jsonb_typeof(detection_evidence) = 'object'}.</li>
 *   <li>{@code uq_endpoint_uninstall_audit_command}: one audit row per
 *       dispatched command.</li>
 *   <li>Composite tenant FK on request/device/catalog/command refs.</li>
 *   <li>Append-only trigger {@code trg_endpoint_uninstall_audit_append_only}.</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8d81-3d87-78f2-ba17-9a8981c5eb16} iter-2 AGREE.
 */
@Entity
@Table(name = "endpoint_uninstall_audit",
        indexes = {
                @Index(name = "ix_endpoint_uninstall_audit_tenant_device_reported",
                        columnList = "tenant_id,device_id,reported_at DESC")
        })
public class EndpointUninstallAudit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Faz 21.1 C4 V49 org_id compat field (Option A). Mapped to the V49
     * {@code org_id} column; nullable in JPA (VALIDATED CHECK is the live
     * enforcement, column-level SET NOT NULL deferred to A6). Canonicalized to
     * {@code tenantId} in {@link #prePersist()} only — this table is append-only
     * (a DB BEFORE UPDATE/DELETE trigger rejects mutations), so there is no
     * {@code @PreUpdate}. Reads stay tenant-keyed (A5); {@link #getEffectiveOrgId()}
     * resolves legacy {@code orgId == null} rows.
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 48)
    private UninstallResultStatus resultStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification", nullable = false, length = 32)
    private UninstallVerification verification;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> redactedPayload = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detectionEvidence = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (redactedPayload == null) {
            redactedPayload = new HashMap<>();
        }
        if (detectionEvidence == null) {
            detectionEvidence = new HashMap<>();
        }
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointUninstallAudit that = (EndpointUninstallAudit) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
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
    public UninstallResultStatus getResultStatus() { return resultStatus; }
    public void setResultStatus(UninstallResultStatus resultStatus) { this.resultStatus = resultStatus; }
    public UninstallVerification getVerification() { return verification; }
    public void setVerification(UninstallVerification verification) { this.verification = verification; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
    public Map<String, Object> getRedactedPayload() { return redactedPayload; }
    public void setRedactedPayload(Map<String, Object> redactedPayload) {
        this.redactedPayload = redactedPayload == null ? new HashMap<>() : redactedPayload;
    }
    public Map<String, Object> getDetectionEvidence() { return detectionEvidence; }
    public void setDetectionEvidence(Map<String, Object> detectionEvidence) {
        this.detectionEvidence = detectionEvidence == null ? new HashMap<>() : detectionEvidence;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
