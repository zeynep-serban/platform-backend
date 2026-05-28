package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Faz 22.3 — Backend mTLS machine-cert self-enrollment record (ADR-0029
 * backend layer). The cert's SAN URI {@code adcomputer:{objectGUID}} is the
 * PRIMARY device identity; {@link #machineFingerprint} is secondary dedupe.
 */
@Entity
@Table(name = "endpoint_machine_certs",
        indexes = {
                @Index(name = "idx_endpoint_machine_certs_tenant_thumbprint",
                        columnList = "tenant_id,cert_thumbprint"),
                @Index(name = "idx_endpoint_machine_certs_object_guid",
                        columnList = "object_guid"),
                @Index(name = "idx_endpoint_machine_certs_tenant_enrolled",
                        columnList = "tenant_id,enrolled_at")
        })
public class EndpointMachineCert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private EndpointDevice device;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "san_uri", nullable = false, length = 512)
    private String sanUri;

    @Column(name = "object_guid", nullable = false)
    private UUID objectGuid;

    @Column(name = "cert_serial", nullable = false, length = 128)
    private String certSerial;

    @Column(name = "cert_thumbprint", nullable = false, length = 128)
    private String certThumbprint;

    @Column(name = "cert_issuer", nullable = false, length = 512)
    private String certIssuer;

    @Column(name = "cert_subject", nullable = false, length = 512)
    private String certSubject;

    @Column(name = "cert_not_before", nullable = false)
    private Instant certNotBefore;

    @Column(name = "cert_not_after", nullable = false)
    private Instant certNotAfter;

    @Column(name = "machine_fingerprint", nullable = false, length = 512)
    private String machineFingerprint;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 512)
    private String revokedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (enrolledAt == null) {
            enrolledAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public EndpointDevice getDevice() {
        return device;
    }

    public void setDevice(EndpointDevice device) {
        this.device = device;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getSanUri() {
        return sanUri;
    }

    public void setSanUri(String sanUri) {
        this.sanUri = sanUri;
    }

    public UUID getObjectGuid() {
        return objectGuid;
    }

    public void setObjectGuid(UUID objectGuid) {
        this.objectGuid = objectGuid;
    }

    public String getCertSerial() {
        return certSerial;
    }

    public void setCertSerial(String certSerial) {
        this.certSerial = certSerial;
    }

    public String getCertThumbprint() {
        return certThumbprint;
    }

    public void setCertThumbprint(String certThumbprint) {
        this.certThumbprint = certThumbprint;
    }

    public String getCertIssuer() {
        return certIssuer;
    }

    public void setCertIssuer(String certIssuer) {
        this.certIssuer = certIssuer;
    }

    public String getCertSubject() {
        return certSubject;
    }

    public void setCertSubject(String certSubject) {
        this.certSubject = certSubject;
    }

    public Instant getCertNotBefore() {
        return certNotBefore;
    }

    public void setCertNotBefore(Instant certNotBefore) {
        this.certNotBefore = certNotBefore;
    }

    public Instant getCertNotAfter() {
        return certNotAfter;
    }

    public void setCertNotAfter(Instant certNotAfter) {
        this.certNotAfter = certNotAfter;
    }

    public String getMachineFingerprint() {
        return machineFingerprint;
    }

    public void setMachineFingerprint(String machineFingerprint) {
        this.machineFingerprint = machineFingerprint;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public void setEnrolledAt(Instant enrolledAt) {
        this.enrolledAt = enrolledAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(String revokedReason) {
        this.revokedReason = revokedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointMachineCert that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
