package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-021 — append-on-terminal-result install audit row (Faz 22.5).
 *
 * <p>One row per {@code INSTALL_SOFTWARE} command terminal result
 * (SUCCEEDED / FAILED / PARTIAL / UNSUPPORTED). Carries the
 * backend-redacted detection payload and the preflight decision
 * snapshot the install was issued under. BE-023 compliance evaluator
 * uses this table as a fallback REQUIRED-satisfaction signal when the
 * inventory snapshot is present and apps-available but the targeted
 * catalog item is not currently observable (Codex 019e6dfb iter-3 P0-4
 * absorb — "minimal/conservative" fallback semantics).
 *
 * <p>Composite-FK pattern (Codex iter-3 P0-3 absorb): all three FKs
 * bind the tenant column so a cross-tenant misrouting is physically
 * impossible. The corresponding {@code (id, tenant_id)} unique
 * constraints on {@code endpoint_commands} and {@code endpoint_devices}
 * are added in {@code V12__endpoint_install_audit.sql}; the catalog
 * side was already prepared by {@code V10__endpoint_compliance_state.sql}.
 *
 * <p>{@code redacted_payload} is the same map written to
 * {@code endpoint_command_results.result_payload.details} for an
 * install — the backend redaction pass runs once before the result
 * row is persisted, so the raw agent payload never lands on disk
 * (Codex iter-3 P0-2 absorb).
 */
@Entity
@Table(name = "endpoint_install_audit",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_endpoint_install_audit_command",
                columnNames = "command_id"),
        indexes = {
                @Index(name = "idx_endpoint_install_audit_tenant_device_time",
                        columnList = "tenant_id,device_id,reported_at"),
                @Index(name = "idx_endpoint_install_audit_tenant_catalog_time",
                        columnList = "tenant_id,catalog_item_id,reported_at"),
                @Index(name = "idx_endpoint_install_audit_tenant_status_time",
                        columnList = "tenant_id,result_status,reported_at")
        })
public class EndpointInstallAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Faz 21.1 PR2b-i org_id compat field (Codex 019e8cac Option A). */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Column(name = "catalog_package_id", nullable = false, length = 255)
    private String catalogPackageId;

    @Column(name = "catalog_row_version", nullable = false)
    private Long catalogRowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "preflight_decision", nullable = false, length = 16)
    private InstallPreflightDecisionRecorded preflightDecision;

    @Column(name = "preflight_decision_at", nullable = false)
    private Instant preflightDecisionAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preflight_warn_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> preflightWarnCodes = new ArrayList<>();

    @Column(name = "actor_subject", nullable = false, length = 255)
    private String actorSubject;

    @Column(name = "approval_subject", length = 255)
    private String approvalSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 32)
    private CommandResultStatus resultStatus;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_verification", nullable = false, length = 16)
    private InstallPostVerification postVerification;

    @Column(name = "detected_package_id", length = 255)
    private String detectedPackageId;

    @Column(name = "detected_version", length = 128)
    private String detectedVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "post_verification_evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> postVerificationEvidence = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> redactedPayload = new HashMap<>();

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (preflightWarnCodes == null) {
            preflightWarnCodes = new ArrayList<>();
        }
        if (postVerificationEvidence == null) {
            postVerificationEvidence = new HashMap<>();
        }
        if (redactedPayload == null) {
            redactedPayload = new HashMap<>();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    /** Faz 21.1 PR2b-i org_id accessor (Codex 019e8cac Option A). */
    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    /** Faz 21.1 PR2b-i effective-org accessor: orgId fallback to tenantId. */
    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public void setCommandId(UUID commandId) {
        this.commandId = commandId;
    }

    public UUID getCatalogItemId() {
        return catalogItemId;
    }

    public void setCatalogItemId(UUID catalogItemId) {
        this.catalogItemId = catalogItemId;
    }

    public String getCatalogPackageId() {
        return catalogPackageId;
    }

    public void setCatalogPackageId(String catalogPackageId) {
        this.catalogPackageId = catalogPackageId;
    }

    public Long getCatalogRowVersion() {
        return catalogRowVersion;
    }

    public void setCatalogRowVersion(Long catalogRowVersion) {
        this.catalogRowVersion = catalogRowVersion;
    }

    public InstallPreflightDecisionRecorded getPreflightDecision() {
        return preflightDecision;
    }

    public void setPreflightDecision(InstallPreflightDecisionRecorded preflightDecision) {
        this.preflightDecision = preflightDecision;
    }

    public Instant getPreflightDecisionAt() {
        return preflightDecisionAt;
    }

    public void setPreflightDecisionAt(Instant preflightDecisionAt) {
        this.preflightDecisionAt = preflightDecisionAt;
    }

    public List<String> getPreflightWarnCodes() {
        return preflightWarnCodes;
    }

    public void setPreflightWarnCodes(List<String> preflightWarnCodes) {
        this.preflightWarnCodes = preflightWarnCodes == null
                ? new ArrayList<>() : preflightWarnCodes;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public void setActorSubject(String actorSubject) {
        this.actorSubject = actorSubject;
    }

    public String getApprovalSubject() {
        return approvalSubject;
    }

    public void setApprovalSubject(String approvalSubject) {
        this.approvalSubject = approvalSubject;
    }

    public CommandResultStatus getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(CommandResultStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(Instant reportedAt) {
        this.reportedAt = reportedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public InstallPostVerification getPostVerification() {
        return postVerification;
    }

    public void setPostVerification(InstallPostVerification postVerification) {
        this.postVerification = postVerification;
    }

    public String getDetectedPackageId() {
        return detectedPackageId;
    }

    public void setDetectedPackageId(String detectedPackageId) {
        this.detectedPackageId = detectedPackageId;
    }

    public String getDetectedVersion() {
        return detectedVersion;
    }

    public void setDetectedVersion(String detectedVersion) {
        this.detectedVersion = detectedVersion;
    }

    public Map<String, Object> getPostVerificationEvidence() {
        return postVerificationEvidence;
    }

    public void setPostVerificationEvidence(Map<String, Object> postVerificationEvidence) {
        this.postVerificationEvidence = postVerificationEvidence == null
                ? new HashMap<>() : postVerificationEvidence;
    }

    public Map<String, Object> getRedactedPayload() {
        return redactedPayload;
    }

    public void setRedactedPayload(Map<String, Object> redactedPayload) {
        this.redactedPayload = redactedPayload == null ? new HashMap<>() : redactedPayload;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointInstallAudit that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
