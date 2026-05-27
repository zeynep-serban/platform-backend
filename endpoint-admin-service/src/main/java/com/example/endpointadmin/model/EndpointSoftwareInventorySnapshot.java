package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * Canonical per-device software inventory snapshot — BE-020I (Faz 22.5.3A).
 *
 * <p>Each tenant + device pair owns at most one row; agent
 * {@code COLLECT_INVENTORY} result ingests upsert the summary fields, and
 * the full apps payload (when present) replaces the child
 * {@link EndpointSoftwareInventoryItem} rows in the same transaction.
 *
 * <p>{@code apps_available} stays {@code true} once a full {@code apps[]}
 * payload has been ingested at least once; subsequent summary-only ingests
 * do NOT flip it back to {@code false} (Codex 019e6ab2 iter-2 acceptance).
 */
@Entity
@Table(name = "endpoint_software_inventory_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_software_inventory_snapshots_tenant_device",
                        columnNames = {"tenant_id", "device_id"})
        },
        indexes = {
                @Index(
                        name = "idx_endpoint_software_inventory_snapshots_tenant_apps_available",
                        columnList = "tenant_id,apps_available"),
                @Index(
                        name = "idx_endpoint_software_inventory_snapshots_device",
                        columnList = "device_id")
        })
public class EndpointSoftwareInventorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private EndpointDevice device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "latest_summary_command_result_id")
    private EndpointCommandResult latestSummaryCommandResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "latest_full_command_result_id")
    private EndpointCommandResult latestFullCommandResult;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "supported", nullable = false)
    private boolean supported;

    @Column(name = "app_count")
    private Integer appCount;

    @Column(name = "apps_stored_count")
    private Integer appsStoredCount;

    @Column(name = "winget_ready")
    private Boolean wingetReady;

    @Column(name = "winget_version", length = 64)
    private String wingetVersion;

    @Column(name = "total_size_kb")
    private Long totalSizeKb;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probe_errors", columnDefinition = "jsonb")
    private Map<String, Object> probeErrors;

    @Column(name = "summary_collected_at")
    private Instant summaryCollectedAt;

    @Column(name = "apps_collected_at")
    private Instant appsCollectedAt;

    @Column(name = "apps_available", nullable = false)
    private boolean appsAvailable = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "snapshot",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<EndpointSoftwareInventoryItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (probeErrors == null) {
            probeErrors = new HashMap<>();
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

    public EndpointDevice getDevice() {
        return device;
    }

    public void setDevice(EndpointDevice device) {
        this.device = device;
    }

    public EndpointCommandResult getLatestSummaryCommandResult() {
        return latestSummaryCommandResult;
    }

    public void setLatestSummaryCommandResult(EndpointCommandResult value) {
        this.latestSummaryCommandResult = value;
    }

    public EndpointCommandResult getLatestFullCommandResult() {
        return latestFullCommandResult;
    }

    public void setLatestFullCommandResult(EndpointCommandResult value) {
        this.latestFullCommandResult = value;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isSupported() {
        return supported;
    }

    public void setSupported(boolean supported) {
        this.supported = supported;
    }

    public Integer getAppCount() {
        return appCount;
    }

    public void setAppCount(Integer appCount) {
        this.appCount = appCount;
    }

    public Integer getAppsStoredCount() {
        return appsStoredCount;
    }

    public void setAppsStoredCount(Integer appsStoredCount) {
        this.appsStoredCount = appsStoredCount;
    }

    public Boolean getWingetReady() {
        return wingetReady;
    }

    public void setWingetReady(Boolean wingetReady) {
        this.wingetReady = wingetReady;
    }

    public String getWingetVersion() {
        return wingetVersion;
    }

    public void setWingetVersion(String wingetVersion) {
        this.wingetVersion = wingetVersion;
    }

    public Long getTotalSizeKb() {
        return totalSizeKb;
    }

    public void setTotalSizeKb(Long totalSizeKb) {
        this.totalSizeKb = totalSizeKb;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public Map<String, Object> getProbeErrors() {
        return probeErrors;
    }

    public void setProbeErrors(Map<String, Object> probeErrors) {
        this.probeErrors = probeErrors == null ? new HashMap<>() : probeErrors;
    }

    public Instant getSummaryCollectedAt() {
        return summaryCollectedAt;
    }

    public void setSummaryCollectedAt(Instant summaryCollectedAt) {
        this.summaryCollectedAt = summaryCollectedAt;
    }

    public Instant getAppsCollectedAt() {
        return appsCollectedAt;
    }

    public void setAppsCollectedAt(Instant appsCollectedAt) {
        this.appsCollectedAt = appsCollectedAt;
    }

    public boolean isAppsAvailable() {
        return appsAvailable;
    }

    public void setAppsAvailable(boolean appsAvailable) {
        this.appsAvailable = appsAvailable;
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

    public List<EndpointSoftwareInventoryItem> getItems() {
        return items;
    }

    /**
     * Atomic full-apps replacement: clear the orphan-removal-managed
     * collection + append the new items. The service layer is the only
     * caller; summary-only ingest paths do NOT call this method.
     */
    public void replaceItems(List<EndpointSoftwareInventoryItem> next) {
        items.clear();
        if (next != null) {
            for (EndpointSoftwareInventoryItem item : next) {
                item.setSnapshot(this);
                item.setTenantId(tenantId);
                if (device != null) {
                    item.setDeviceId(device.getId());
                }
                items.add(item);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointSoftwareInventorySnapshot that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
