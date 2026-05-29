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
 * BE — append-only device-health snapshot (Faz 22.5, AG-033 ingest).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that
 * carried a {@code details.inventory.deviceHealth} block. Mirrors the
 * BE-022 {@link EndpointHardwareInventorySnapshot} precedent EXACTLY:
 * composite-FK tenant integrity, append-only history, partial-UNIQUE
 * idempotency on {@code source_command_result_id}, and a payload-hash
 * dedupe column.
 *
 * <p>Redaction boundary (security invariant — do not widen): disk
 * facets carry ONLY {@code driveLetter} ({@code ^[A-Z]:$}) — NO volume
 * label / serial / filesystem / mount path / GUID. {@code lastBootEpochSec}
 * is unix seconds, never a local-time string. {@code probeError.summary}
 * is bounded operator text. The {@code DeviceHealthPayloadPolicy}
 * pre-persist hook fail-closed rejects out-of-shape payloads before the
 * command-result row is even saved.
 *
 * <p>Composite-FK pattern: the {@code (device_id, tenant_id)} FK to
 * {@code endpoint_devices(id, tenant_id)} physically forbids a
 * cross-tenant misrouting. The child table
 * {@code endpoint_device_health_disks} binds via {@code (snapshot_id,
 * tenant_id)}.
 *
 * <p>Append-only history: the snapshot table has no UNIQUE on
 * {@code (tenant_id, device_id)} — every successful ingest produces a
 * new row. {@code latest} queries use {@code ORDER BY collected_at DESC,
 * created_at DESC, id DESC} with the matching composite index.
 *
 * <p>{@code probeComplete=false} is fail-closed: treat as "evidence
 * incomplete", never render the zero-values as a healthy device.
 */
@Entity
@Table(name = "endpoint_device_health_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_endpoint_device_health_snapshots_id_tenant",
                columnNames = {"id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_endpoint_device_health_snapshots_tenant_device_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointDeviceHealthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

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

    @Column(name = "any_low_disk", nullable = false)
    private Boolean anyLowDisk;

    @Column(name = "fixed_disk_count", nullable = false)
    private Integer fixedDiskCount;

    @Column(name = "fixed_disks_truncated", nullable = false)
    private Boolean fixedDisksTruncated;

    @Column(name = "max_fixed_disks", nullable = false)
    private Integer maxFixedDisks;

    @Column(name = "memory_used_percent")
    private Short memoryUsedPercent;

    @Column(name = "memory_high_pressure")
    private Boolean memoryHighPressure;

    @Column(name = "uptime_days")
    private Integer uptimeDays;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds;

    @Column(name = "last_boot_epoch_sec")
    private Long lastBootEpochSec;

    @Column(name = "long_uptime_warning")
    private Boolean longUptimeWarning;

    @Column(name = "source_used", nullable = false, length = 8)
    private String sourceUsed;

    @Column(name = "probe_duration_ms")
    private Integer probeDurationMs;

    // VARCHAR(64) (not CHAR(64)) so Hibernate ddl-auto=validate is
    // satisfied (BE-022 V14 lesson). The DB CHECK
    // `payload_hash_sha256 ~ '^[a-f0-9]{64}$'` enforces the exact
    // 64-char lowercase SHA-256 hex shape. The dedupe query uses a
    // direct VARCHAR `=` (never lower(bytea)).
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

    /** Child fixed-disk facets. CascadeType.ALL + orphanRemoval mirrors
     * the V13 hardware-inventory disk relation. */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    private List<EndpointDeviceHealthDisk> disks = new ArrayList<>();

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

    public Boolean getAnyLowDisk() {
        return anyLowDisk;
    }

    public void setAnyLowDisk(Boolean anyLowDisk) {
        this.anyLowDisk = anyLowDisk;
    }

    public Integer getFixedDiskCount() {
        return fixedDiskCount;
    }

    public void setFixedDiskCount(Integer fixedDiskCount) {
        this.fixedDiskCount = fixedDiskCount;
    }

    public Boolean getFixedDisksTruncated() {
        return fixedDisksTruncated;
    }

    public void setFixedDisksTruncated(Boolean fixedDisksTruncated) {
        this.fixedDisksTruncated = fixedDisksTruncated;
    }

    public Integer getMaxFixedDisks() {
        return maxFixedDisks;
    }

    public void setMaxFixedDisks(Integer maxFixedDisks) {
        this.maxFixedDisks = maxFixedDisks;
    }

    public Short getMemoryUsedPercent() {
        return memoryUsedPercent;
    }

    public void setMemoryUsedPercent(Short memoryUsedPercent) {
        this.memoryUsedPercent = memoryUsedPercent;
    }

    public Boolean getMemoryHighPressure() {
        return memoryHighPressure;
    }

    public void setMemoryHighPressure(Boolean memoryHighPressure) {
        this.memoryHighPressure = memoryHighPressure;
    }

    public Integer getUptimeDays() {
        return uptimeDays;
    }

    public void setUptimeDays(Integer uptimeDays) {
        this.uptimeDays = uptimeDays;
    }

    public Long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(Long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }

    public Long getLastBootEpochSec() {
        return lastBootEpochSec;
    }

    public void setLastBootEpochSec(Long lastBootEpochSec) {
        this.lastBootEpochSec = lastBootEpochSec;
    }

    public Boolean getLongUptimeWarning() {
        return longUptimeWarning;
    }

    public void setLongUptimeWarning(Boolean longUptimeWarning) {
        this.longUptimeWarning = longUptimeWarning;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public List<EndpointDeviceHealthDisk> getDisks() {
        return disks;
    }

    public void setDisks(List<EndpointDeviceHealthDisk> disks) {
        this.disks = disks == null ? new ArrayList<>() : disks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointDeviceHealthSnapshot that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
