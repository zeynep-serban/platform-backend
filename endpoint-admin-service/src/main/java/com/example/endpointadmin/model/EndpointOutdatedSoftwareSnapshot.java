package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * BE — append-only outdated-software snapshot (Faz 22.5, AG-036 ingest).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that
 * carried a {@code details.inventory.outdatedSoftware} block. Mirrors the
 * BE-022 {@link EndpointHardwareInventorySnapshot} + AG-033
 * {@link EndpointDeviceHealthSnapshot} precedents EXACTLY: composite-FK
 * tenant integrity, append-only history, partial-UNIQUE idempotency on
 * {@code source_command_result_id}, and a payload-hash dedupe column.
 *
 * <p>Redaction boundary (security invariant — do not widen): the child
 * package facets carry ONLY {@code packageId}, {@code installedVersion},
 * {@code availableVersion} — NO display name / publisher / install
 * location / license / download URL. {@code probeError.summary} is bounded
 * operator text. The {@code OutdatedSoftwarePayloadPolicy} pre-persist hook
 * fail-closed rejects out-of-shape payloads before the command-result row
 * is even saved.
 *
 * <p>Read-only boundary: the underlying probe is read-only ('winget
 * upgrade --include-returning-apps --source winget'); persisting this
 * snapshot never triggers any agent-side mutation.
 *
 * <p>Composite-FK pattern: the {@code (device_id, tenant_id)} FK to
 * {@code endpoint_devices(id, tenant_id)} physically forbids a
 * cross-tenant misrouting. The child table
 * {@code endpoint_outdated_software_packages} binds via {@code (snapshot_id,
 * tenant_id)}.
 *
 * <p>Append-only history: the snapshot table has no UNIQUE on
 * {@code (tenant_id, device_id)} — every successful ingest produces a new
 * row. {@code latest} queries use {@code ORDER BY collected_at DESC,
 * created_at DESC, id DESC} with the matching composite index.
 *
 * <p>{@code supported=false} / {@code probeComplete=false} are fail-closed:
 * persist as evidence, never render an incomplete probe as "fully up to
 * date". The "possibly truncated" rendering hint is computed at the DTO /
 * audit-event boundary via
 * {@link com.example.endpointadmin.service.OutdatedSnapshotTruncation}:
 * {@code upgradeTruncated == TRUE} (agent authoritative,
 * post-platform-agent #40) OR {@code upgradeCount >= maxUpgrade}
 * (defence-in-depth fallback). #1148.
 */
@Entity
@Table(name = "endpoint_outdated_software_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_endpoint_outdated_software_snapshots_id_tenant",
                columnNames = {"id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_endpoint_outdated_software_snapshots_tenant_device_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointOutdatedSoftwareSnapshot {

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

    /** Pointer to the originating agent command-result; NULL for
     * manual/test ingest paths. UNIQUE (partial) at the DB layer. */
    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Short schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "probe_complete", nullable = false)
    private Boolean probeComplete;

    @Column(name = "upgrade_count", nullable = false)
    private Integer upgradeCount;

    @Column(name = "upgrade_truncated", nullable = false)
    private Boolean upgradeTruncated;

    @Column(name = "max_upgrade", nullable = false)
    private Integer maxUpgrade;

    @Column(name = "source_used", nullable = false, length = 8)
    private String sourceUsed;

    @Column(name = "probe_duration_ms")
    private Integer probeDurationMs;

    // VARCHAR(64) (not CHAR(64)) so Hibernate ddl-auto=validate is
    // satisfied (BE-022 V14 lesson). The DB CHECK
    // `payload_hash_sha256 ~ '^[a-f0-9]{64}$'` enforces the exact
    // 64-char lowercase SHA-256 hex shape. The dedupe query uses a
    // direct VARCHAR `=` via cast(:hash as string) (never lower(bytea)).
    @Column(name = "payload_hash_sha256", nullable = false, length = 64)
    private String payloadHashSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> redactedPayload = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probe_errors", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> probeErrors = new ArrayList<>();

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Child upgradeable-package facets. CascadeType.ALL + orphanRemoval
     * mirrors the V13/V17 disk relation. */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    private List<EndpointOutdatedSoftwarePackage> packages = new ArrayList<>();

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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

    public UUID getSourceCommandResultId() {
        return sourceCommandResultId;
    }

    public void setSourceCommandResultId(UUID sourceCommandResultId) {
        this.sourceCommandResultId = sourceCommandResultId;
    }

    public Short getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Short schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Boolean getSupported() {
        return supported;
    }

    public void setSupported(Boolean supported) {
        this.supported = supported;
    }

    public Boolean getProbeComplete() {
        return probeComplete;
    }

    public void setProbeComplete(Boolean probeComplete) {
        this.probeComplete = probeComplete;
    }

    public Integer getUpgradeCount() {
        return upgradeCount;
    }

    public void setUpgradeCount(Integer upgradeCount) {
        this.upgradeCount = upgradeCount;
    }

    public Boolean getUpgradeTruncated() {
        return upgradeTruncated;
    }

    public void setUpgradeTruncated(Boolean upgradeTruncated) {
        this.upgradeTruncated = upgradeTruncated;
    }

    public Integer getMaxUpgrade() {
        return maxUpgrade;
    }

    public void setMaxUpgrade(Integer maxUpgrade) {
        this.maxUpgrade = maxUpgrade;
    }

    public String getSourceUsed() {
        return sourceUsed;
    }

    public void setSourceUsed(String sourceUsed) {
        this.sourceUsed = sourceUsed;
    }

    public Integer getProbeDurationMs() {
        return probeDurationMs;
    }

    public void setProbeDurationMs(Integer probeDurationMs) {
        this.probeDurationMs = probeDurationMs;
    }

    public String getPayloadHashSha256() {
        return payloadHashSha256;
    }

    public void setPayloadHashSha256(String payloadHashSha256) {
        this.payloadHashSha256 = payloadHashSha256;
    }

    public Map<String, Object> getRedactedPayload() {
        return redactedPayload;
    }

    public void setRedactedPayload(Map<String, Object> redactedPayload) {
        this.redactedPayload = redactedPayload == null ? new HashMap<>() : redactedPayload;
    }

    public List<Map<String, Object>> getProbeErrors() {
        return probeErrors;
    }

    public void setProbeErrors(List<Map<String, Object>> probeErrors) {
        this.probeErrors = probeErrors == null ? new ArrayList<>() : probeErrors;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public List<EndpointOutdatedSoftwarePackage> getPackages() {
        return packages;
    }

    public void setPackages(List<EndpointOutdatedSoftwarePackage> packages) {
        this.packages = packages == null ? new ArrayList<>() : packages;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointOutdatedSoftwareSnapshot that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
