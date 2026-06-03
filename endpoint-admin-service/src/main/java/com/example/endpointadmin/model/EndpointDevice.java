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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_devices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_endpoint_devices_tenant_hostname",
                        columnNames = {"tenant_id", "hostname"}),
                @UniqueConstraint(name = "uq_endpoint_devices_tenant_fingerprint",
                        columnNames = {"tenant_id", "machine_fingerprint"})
        },
        indexes = {
                @Index(name = "idx_endpoint_devices_tenant_status", columnList = "tenant_id,status"),
                @Index(name = "idx_endpoint_devices_last_seen", columnList = "last_seen_at"),
                @Index(name = "idx_endpoint_devices_domain", columnList = "domain_name")
        })
public class EndpointDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Faz 21.1 PR2b-i org_id compat field (Codex 019e8cac plan-time AGREE
     * Option A). Mapped to the V29 {@code org_id} column. Nullable in JPA
     * (V29 schema kept nullable; V30 CHECK constraint enforces
     * {@code org_id IS NULL OR org_id = tenant_id}). Service-layer
     * canonical write path (PR2b-ii) sets BOTH this and {@code tenantId}
     * to the same UUID. Repository / query layer reads through
     * {@link #getEffectiveOrgId()} so legacy rows
     * ({@code orgId IS NULL}, {@code tenantId NOT NULL}) still resolve.
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "hostname", nullable = false)
    private String hostname;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "os_type", nullable = false, length = 32)
    private OsType osType = OsType.UNKNOWN;

    @Column(name = "os_version")
    private String osVersion;

    @Column(name = "agent_version", length = 128)
    private String agentVersion;

    @Column(name = "machine_fingerprint", length = 512)
    private String machineFingerprint;

    @Column(name = "domain_name")
    private String domainName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeviceStatus status = DeviceStatus.PENDING_ENROLLMENT;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
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

    /**
     * Faz 21.1 PR2b-i org_id accessor. May return {@code null} on legacy
     * rows where the canonical write path has not (yet) populated the
     * column. Read paths should call {@link #getEffectiveOrgId()} so
     * legacy rows still resolve a tenant scope.
     */
    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    /**
     * Faz 21.1 PR2b-i effective-org accessor (Codex 019e8cac Option A).
     * Returns {@code orgId} when populated (canonical post-PR2b-ii
     * write path) else falls back to {@code tenantId} (legacy rows).
     * V30 CHECK constraint guarantees, when {@code orgId IS NOT NULL},
     * it equals {@code tenantId}, so the two paths are observably
     * indistinguishable for downstream consumers.
     */
    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public OsType getOsType() {
        return osType;
    }

    public void setOsType(OsType osType) {
        this.osType = osType;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getMachineFingerprint() {
        return machineFingerprint;
    }

    public void setMachineFingerprint(String machineFingerprint) {
        this.machineFingerprint = machineFingerprint;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public void setEnrolledAt(Instant enrolledAt) {
        this.enrolledAt = enrolledAt;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointDevice that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
