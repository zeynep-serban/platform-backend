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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-017 — the terminal dual-control decision recorded for a destructive
 * {@link EndpointCommand}. Exactly one row per command (unique on
 * {@code command_id}); the issuer ({@code issuerSubject}) and the deciding
 * admin ({@code decidedBySubject}) are always distinct (enforced in the
 * service layer).
 */
@Entity
@Table(name = "endpoint_command_approvals",
        uniqueConstraints = @UniqueConstraint(name = "uq_endpoint_command_approvals_command",
                columnNames = "command_id"),
        indexes = @Index(name = "idx_endpoint_command_approvals_tenant", columnList = "tenant_id"))
public class EndpointCommandApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    @Column(name = "issuer_subject", nullable = false, length = 255)
    private String issuerSubject;

    @Column(name = "decided_by_subject", nullable = false, length = 255)
    private String decidedBySubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private ApprovalDecision decision;

    @Column(name = "reason", length = 512)
    private String reason;

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

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public void setCommandId(UUID commandId) {
        this.commandId = commandId;
    }

    public String getIssuerSubject() {
        return issuerSubject;
    }

    public void setIssuerSubject(String issuerSubject) {
        this.issuerSubject = issuerSubject;
    }

    public String getDecidedBySubject() {
        return decidedBySubject;
    }

    public void setDecidedBySubject(String decidedBySubject) {
        this.decidedBySubject = decidedBySubject;
    }

    public ApprovalDecision getDecision() {
        return decision;
    }

    public void setDecision(ApprovalDecision decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
        if (!(o instanceof EndpointCommandApproval that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
