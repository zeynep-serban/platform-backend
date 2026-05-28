package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Latest-pointer read model for per-device compliance state — BE-023
 * (Faz 22.5).
 *
 * <p>Single row per {@code (tenantId, deviceId)} composite key,
 * updated in place by {@code EndpointComplianceService} after a new
 * {@link EndpointComplianceEvaluation} history row is appended. The
 * hot path (GET /compliance, GET /compliance/devices) reads this
 * table; history pagination reads the evaluation table.
 *
 * <p>Concurrency is provided by the per-(tenant, device)
 * {@code pg_try_advisory_xact_lock} the service acquires inside the
 * evaluation transaction (Codex 019e6bbf iter-2 + iter-3). There is no
 * second optimistic {@code @Version} layer beyond the row-level
 * {@code version} column kept for Hibernate book-keeping.
 */
@Entity
@Table(name = "endpoint_device_compliance_states",
        indexes = {
                @Index(name = "idx_endpoint_device_compliance_states_tenant_decision_time",
                        columnList = "tenant_id,decision,evaluated_at")
        })
@IdClass(EndpointDeviceComplianceState.PK.class)
public class EndpointDeviceComplianceState {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "latest_evaluation_id", nullable = false)
    private UUID latestEvaluationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private ComplianceDecision decision;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getLatestEvaluationId() {
        return latestEvaluationId;
    }

    public void setLatestEvaluationId(UUID latestEvaluationId) {
        this.latestEvaluationId = latestEvaluationId;
    }

    public ComplianceDecision getDecision() {
        return decision;
    }

    public void setDecision(ComplianceDecision decision) {
        this.decision = decision;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointDeviceComplianceState that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId)
                && Objects.equals(deviceId, that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, deviceId);
    }

    /**
     * Composite primary-key id-class for {@link EndpointDeviceComplianceState}.
     */
    public static class PK implements Serializable {

        private UUID tenantId;
        private UUID deviceId;

        public PK() {
        }

        public PK(UUID tenantId, UUID deviceId) {
            this.tenantId = tenantId;
            this.deviceId = deviceId;
        }

        public UUID getTenantId() {
            return tenantId;
        }

        public void setTenantId(UUID tenantId) {
            this.tenantId = tenantId;
        }

        public UUID getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(UUID deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PK pk)) {
                return false;
            }
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(deviceId, pk.deviceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, deviceId);
        }
    }
}
