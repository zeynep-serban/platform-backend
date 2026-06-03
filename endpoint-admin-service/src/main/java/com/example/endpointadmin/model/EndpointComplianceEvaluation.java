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
 * Append-only compliance evaluation history row — BE-023 (Faz 22.5).
 *
 * <p>Every invocation of
 * {@code EndpointComplianceService.evaluate(...)} that successfully
 * acquires the per-(tenant, device) advisory lock writes one row of
 * this entity and updates the read-model pointer
 * ({@link EndpointDeviceComplianceState}) in the same transaction.
 * Audit / debug queries replay history; the hot path reads only the
 * pointer.
 *
 * <p>{@code evidence} is a JSONB block containing the deterministic
 * snapshot the decision was made against (per-stream collectedAt,
 * command result ids, matched-app summaries). {@code catalogPolicyHash}
 * is a SHA-256 over a canonical-sorted projection of every policy +
 * catalog row that was visible at evaluation time, so a later audit
 * can prove which policy set produced the verdict without re-reading
 * historical rows.
 */
@Entity
@Table(name = "endpoint_compliance_evaluations",
        indexes = {
                @Index(name = "idx_endpoint_compliance_evaluations_tenant_device_time",
                        columnList = "tenant_id,device_id,evaluated_at"),
                @Index(name = "idx_endpoint_compliance_evaluations_tenant_decision_time",
                        columnList = "tenant_id,decision,evaluated_at")
        })
public class EndpointComplianceEvaluation {

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

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private ComplianceDecision decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false, columnDefinition = "jsonb")
    private List<String> reasons = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blocking_reasons", nullable = false, columnDefinition = "jsonb")
    private List<String> blockingReasons = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false, columnDefinition = "jsonb")
    private List<String> warnings = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence = new HashMap<>();

    @Column(name = "catalog_policy_hash", nullable = false, length = 64)
    private String catalogPolicyHash;

    @Column(name = "inventory_snapshot_id")
    private UUID inventorySnapshotId;

    @Column(name = "inventory_snapshot_row_version")
    private Long inventorySnapshotRowVersion;

    @Column(name = "catalog_row_version_max")
    private Long catalogRowVersionMax;

    @Column(name = "policy_row_version_max")
    private Long policyRowVersionMax;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (reasons == null) {
            reasons = new ArrayList<>();
        }
        if (blockingReasons == null) {
            blockingReasons = new ArrayList<>();
        }
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        if (evidence == null) {
            evidence = new HashMap<>();
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

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public ComplianceDecision getDecision() {
        return decision;
    }

    public void setDecision(ComplianceDecision decision) {
        this.decision = decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons == null ? new ArrayList<>() : reasons;
    }

    public List<String> getBlockingReasons() {
        return blockingReasons;
    }

    public void setBlockingReasons(List<String> blockingReasons) {
        this.blockingReasons = blockingReasons == null ? new ArrayList<>() : blockingReasons;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public void setEvidence(Map<String, Object> evidence) {
        this.evidence = evidence == null ? new HashMap<>() : evidence;
    }

    public String getCatalogPolicyHash() {
        return catalogPolicyHash;
    }

    public void setCatalogPolicyHash(String catalogPolicyHash) {
        this.catalogPolicyHash = catalogPolicyHash;
    }

    public UUID getInventorySnapshotId() {
        return inventorySnapshotId;
    }

    public void setInventorySnapshotId(UUID inventorySnapshotId) {
        this.inventorySnapshotId = inventorySnapshotId;
    }

    public Long getInventorySnapshotRowVersion() {
        return inventorySnapshotRowVersion;
    }

    public void setInventorySnapshotRowVersion(Long inventorySnapshotRowVersion) {
        this.inventorySnapshotRowVersion = inventorySnapshotRowVersion;
    }

    public Long getCatalogRowVersionMax() {
        return catalogRowVersionMax;
    }

    public void setCatalogRowVersionMax(Long catalogRowVersionMax) {
        this.catalogRowVersionMax = catalogRowVersionMax;
    }

    public Long getPolicyRowVersionMax() {
        return policyRowVersionMax;
    }

    public void setPolicyRowVersionMax(Long policyRowVersionMax) {
        this.policyRowVersionMax = policyRowVersionMax;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointComplianceEvaluation that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
