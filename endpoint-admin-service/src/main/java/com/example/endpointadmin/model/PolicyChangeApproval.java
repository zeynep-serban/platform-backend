package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Wave-12 PR-5 — a policy-change approval request raised against a policy
 * (target = policyId). Mirrors the platform-web {@code ApprovalRequest}
 * contract plus {@code PolicyApprovalDomainExtras}; the {@code type}
 * discriminator from that contract is fixed to {@code policy_change} for
 * every row here (this table is the policy-change specialisation).
 *
 * <p>{@link PolicyChangeApprovalDecision} rows form the {@code history}
 * field — append-only, ordered by {@code sequence}. {@code currentApprovers}
 * is stored as a JSON array on this row because it is rewritten atomically
 * by {@code delegate} and is small in practice (a handful of actors).
 *
 * <p>The 4-eyes guard (proposer cannot approve own request) is enforced in
 * the service layer, not in the schema.
 */
@Entity
@Table(name = "policy_change_approvals",
        indexes = {
                @Index(name = "idx_policy_change_approvals_tenant_status",
                        columnList = "tenant_id,status"),
                @Index(name = "idx_policy_change_approvals_tenant_target",
                        columnList = "tenant_id,target"),
                @Index(name = "idx_policy_change_approvals_tenant_proposer",
                        columnList = "tenant_id,proposer_subject")
        })
public class PolicyChangeApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Target policyId — opaque to this service; carried verbatim. */
    @Column(name = "target", nullable = false, length = 255)
    private String target;

    @Column(name = "proposer_subject", nullable = false, length = 255)
    private String proposerSubject;

    @Column(name = "proposer_name", nullable = false, length = 255)
    private String proposerName;

    @Column(name = "proposer_role", nullable = false, length = 64)
    private String proposerRole;

    @Column(name = "reason", nullable = false, length = 2048)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", columnDefinition = "jsonb")
    private List<String> evidenceRefs;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", nullable = false, length = 16)
    private PolicyChangeKind changeKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 16)
    private PolicyRiskTier riskTier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @Column(name = "deadline")
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PolicyApprovalStatus status = PolicyApprovalStatus.PENDING;

    /**
     * Current approver list serialised as a JSON array of
     * {@code [{id,name,role}]}. Rewritten atomically by delegate; the
     * service layer is the single writer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_approvers", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> currentApprovers = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @OneToMany(mappedBy = "approval", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    private List<PolicyChangeApprovalDecision> history = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void addDecision(PolicyChangeApprovalDecision decision) {
        decision.setApproval(this);
        decision.setSequence(history.size());
        history.add(decision);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getProposerSubject() {
        return proposerSubject;
    }

    public void setProposerSubject(String proposerSubject) {
        this.proposerSubject = proposerSubject;
    }

    public String getProposerName() {
        return proposerName;
    }

    public void setProposerName(String proposerName) {
        this.proposerName = proposerName;
    }

    public String getProposerRole() {
        return proposerRole;
    }

    public void setProposerRole(String proposerRole) {
        this.proposerRole = proposerRole;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public PolicyChangeKind getChangeKind() {
        return changeKind;
    }

    public void setChangeKind(PolicyChangeKind changeKind) {
        this.changeKind = changeKind;
    }

    public PolicyRiskTier getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(PolicyRiskTier riskTier) {
        this.riskTier = riskTier;
    }

    public Map<String, Object> getBeforeState() {
        return beforeState;
    }

    public void setBeforeState(Map<String, Object> beforeState) {
        this.beforeState = beforeState;
    }

    public Map<String, Object> getAfterState() {
        return afterState;
    }

    public void setAfterState(Map<String, Object> afterState) {
        this.afterState = afterState;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public PolicyApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(PolicyApprovalStatus status) {
        this.status = status;
    }

    public List<Map<String, Object>> getCurrentApprovers() {
        return currentApprovers;
    }

    public void setCurrentApprovers(List<Map<String, Object>> currentApprovers) {
        this.currentApprovers = currentApprovers == null
                ? new ArrayList<>()
                : currentApprovers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public List<PolicyChangeApprovalDecision> getHistory() {
        return history;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolicyChangeApproval that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
