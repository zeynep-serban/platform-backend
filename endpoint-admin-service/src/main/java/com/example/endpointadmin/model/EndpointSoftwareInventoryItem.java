package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-software-application row hanging off an
 * {@link EndpointSoftwareInventorySnapshot} (BE-020I, Faz 22.5.3A).
 *
 * <p>The full-apps ingest path deletes every prior item for a snapshot then
 * inserts the new set atomically. Summary-only ingests do NOT touch items.
 * Raw vendor strings (uninstall command, MSI ProductCode GUID, full
 * {@code C:\\Users\\...} paths) are rejected at the service-layer policy
 * validator before the parent {@code endpoint_command_results} row even
 * gets persisted.
 *
 * <p>{@code msi_product_code_hash} accepts the agent wire format
 * {@code sha256:<16hex>} (Codex 019e6ab2 iter-2 acceptance) — never the
 * raw GUID.
 */
@Entity
@Table(name = "endpoint_software_inventory_items",
        indexes = {
                @Index(
                        name = "idx_endpoint_software_inventory_items_tenant_device",
                        columnList = "tenant_id,device_id"),
                @Index(
                        name = "idx_endpoint_software_inventory_items_tenant_publisher",
                        columnList = "tenant_id,publisher")
        })
public class EndpointSoftwareInventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private EndpointSoftwareInventorySnapshot snapshot;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(name = "display_version", length = 128)
    private String displayVersion;

    @Column(name = "publisher", length = 256)
    private String publisher;

    @Column(name = "install_date", length = 32)
    private String installDate;

    @Column(name = "estimated_size_kb")
    private Long estimatedSizeKb;

    @Column(name = "architecture", length = 16)
    private String architecture;

    @Enumerated(EnumType.STRING)
    @Column(name = "install_source", nullable = false, length = 32)
    private SoftwareInstallSource installSource;

    @Column(name = "uninstall_string_present", nullable = false)
    private boolean uninstallStringPresent = false;

    @Column(name = "msi_product_code_hash", length = 64)
    private String msiProductCodeHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_item", columnDefinition = "jsonb")
    private Map<String, Object> rawItem;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (rawItem == null) {
            rawItem = new HashMap<>();
        }
    }

    public UUID getId() {
        return id;
    }

    public EndpointSoftwareInventorySnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(EndpointSoftwareInventorySnapshot snapshot) {
        this.snapshot = snapshot;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayVersion() {
        return displayVersion;
    }

    public void setDisplayVersion(String displayVersion) {
        this.displayVersion = displayVersion;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getInstallDate() {
        return installDate;
    }

    public void setInstallDate(String installDate) {
        this.installDate = installDate;
    }

    public Long getEstimatedSizeKb() {
        return estimatedSizeKb;
    }

    public void setEstimatedSizeKb(Long estimatedSizeKb) {
        this.estimatedSizeKb = estimatedSizeKb;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public SoftwareInstallSource getInstallSource() {
        return installSource;
    }

    public void setInstallSource(SoftwareInstallSource installSource) {
        this.installSource = installSource;
    }

    public boolean isUninstallStringPresent() {
        return uninstallStringPresent;
    }

    public void setUninstallStringPresent(boolean uninstallStringPresent) {
        this.uninstallStringPresent = uninstallStringPresent;
    }

    public String getMsiProductCodeHash() {
        return msiProductCodeHash;
    }

    public void setMsiProductCodeHash(String msiProductCodeHash) {
        this.msiProductCodeHash = msiProductCodeHash;
    }

    public Map<String, Object> getRawItem() {
        return rawItem;
    }

    public void setRawItem(Map<String, Object> rawItem) {
        this.rawItem = rawItem == null ? new HashMap<>() : rawItem;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointSoftwareInventoryItem that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
