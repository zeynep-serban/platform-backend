package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wave-12 PR-5 — one entry of a policy-change approval's append-only
 * history. The {@code kind} discriminator selects which optional columns
 * are populated:
 *
 * <ul>
 *   <li>{@code APPROVE} — {@code reason} optional;</li>
 *   <li>{@code REJECT} / {@code REQUEST_CHANGES} — {@code reason} required;</li>
 *   <li>{@code DELEGATE} — {@code delegateSubject/Name/Role} required,
 *       {@code reason} optional;</li>
 *   <li>{@code ATTEST} — {@code statement} required, {@code acceptedAt}
 *       required.</li>
 * </ul>
 *
 * <p>Service-layer validation enforces these required-by-kind invariants.
 *
 * <p>{@code previousStatus} and {@code newStatus} capture the parent
 * request's status transition the decision triggered, so the
 * design-system {@code DecisionRecordBase} contract round-trips
 * verbatim.
 */
@Entity
@Table(name = "policy_change_approval_decisions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_policy_change_approval_decisions_seq",
                columnNames = {"approval_id", "sequence"}),
        indexes = @Index(name = "idx_policy_change_approval_decisions_approval",
                columnList = "approval_id,sequence"))
public class PolicyChangeApprovalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "approval_id", nullable = false)
    private PolicyChangeApproval approval;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private PolicyApprovalDecisionKind kind;

    @Column(name = "actor_subject", nullable = false, length = 255)
    private String actorSubject;

    @Column(name = "actor_name", nullable = false, length = 255)
    private String actorName;

    @Column(name = "actor_role", nullable = false, length = 64)
    private String actorRole;

    @Column(name = "delegate_subject", length = 255)
    private String delegateSubject;

    @Column(name = "delegate_name", length = 255)
    private String delegateName;

    @Column(name = "delegate_role", length = 64)
    private String delegateRole;

    @Column(name = "reason", length = 2048)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", columnDefinition = "jsonb")
    private List<String> evidenceRefs;

    @Column(name = "statement", length = 4096)
    private String statement;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 32)
    private PolicyApprovalStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32)
    private PolicyApprovalStatus newStatus;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public PolicyChangeApproval getApproval() {
        return approval;
    }

    public void setApproval(PolicyChangeApproval approval) {
        this.approval = approval;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public PolicyApprovalDecisionKind getKind() {
        return kind;
    }

    public void setKind(PolicyApprovalDecisionKind kind) {
        this.kind = kind;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public void setActorSubject(String actorSubject) {
        this.actorSubject = actorSubject;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getDelegateSubject() {
        return delegateSubject;
    }

    public void setDelegateSubject(String delegateSubject) {
        this.delegateSubject = delegateSubject;
    }

    public String getDelegateName() {
        return delegateName;
    }

    public void setDelegateName(String delegateName) {
        this.delegateName = delegateName;
    }

    public String getDelegateRole() {
        return delegateRole;
    }

    public void setDelegateRole(String delegateRole) {
        this.delegateRole = delegateRole;
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

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public PolicyApprovalStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(PolicyApprovalStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public PolicyApprovalStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(PolicyApprovalStatus newStatus) {
        this.newStatus = newStatus;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PolicyChangeApprovalDecision that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
