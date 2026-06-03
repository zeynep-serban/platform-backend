package com.example.endpointadmin.model;

import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse.DiffStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * BE-024c software diff summary cache row (Faz 22.5 P2-A v2-c-pre, Codex
 * 019e88b5 iter-5 AGREE). One row per {@code (tenant_id, device_id)} —
 * the latest computed delta between the latest two
 * {@code endpoint_software_inventory_state_history} captures.
 *
 * <p>v2-d grid SCHEMA v5 (separate PR) joins this table for the
 * {@code software_diff_*} grid columns. The drawer endpoint keeps the
 * canonical on-demand full-list compute path.
 *
 * <p>Status / source-id pairing semantics are PG-enforced
 * (see V27 {@code swdc_status_shape_ck} + {@code swdc_non_ok_counts_zero_ck}).
 */
@Entity
@Table(name = "endpoint_software_diff_cache")
public class EndpointSoftwareDiffCacheRow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * Faz 21.1 PR2c — endpoint org_id canonicalize compat field.
     * Nullable until cleanup PR drops tenant_id. V33 trigger fills this
     * from tenant_id when caller leaves it null; V33 CHECK enforces
     * org_id IS NULL OR org_id = tenant_id (V30 mirror).
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "from_history_id")
    private UUID fromHistoryId;

    @Column(name = "to_history_id")
    private UUID toHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DiffStatus status;

    @Column(name = "added_count", nullable = false)
    private int addedCount;

    @Column(name = "removed_count", nullable = false)
    private int removedCount;

    @Column(name = "version_changed_count", nullable = false)
    private int versionChangedCount;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    // BE-024c v2-c-pre-2-C-A V28 source-pair ordering tuple columns.
    // NOT NULL in DB (post-V28); epoch/zero-UUID sentinel for NO_HISTORY
    // rows or pre-V28 backfilled rows. Backfill / read-only queries can
    // filter by tuple via these fields.
    @Column(name = "source_captured_at", nullable = false)
    private Instant sourceCapturedAt;

    @Column(name = "source_created_at", nullable = false)
    private Instant sourceCreatedAt;

    @Column(name = "source_row_id", nullable = false)
    private UUID sourceRowId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public EndpointSoftwareDiffCacheRow() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    /** Faz 21.1 PR2c — org_id accessor. */
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

    public UUID getFromHistoryId() { return fromHistoryId; }
    public void setFromHistoryId(UUID fromHistoryId) { this.fromHistoryId = fromHistoryId; }

    public UUID getToHistoryId() { return toHistoryId; }
    public void setToHistoryId(UUID toHistoryId) { this.toHistoryId = toHistoryId; }

    public DiffStatus getStatus() { return status; }
    public void setStatus(DiffStatus status) { this.status = status; }

    public int getAddedCount() { return addedCount; }
    public void setAddedCount(int addedCount) { this.addedCount = addedCount; }

    public int getRemovedCount() { return removedCount; }
    public void setRemovedCount(int removedCount) { this.removedCount = removedCount; }

    public int getVersionChangedCount() { return versionChangedCount; }
    public void setVersionChangedCount(int versionChangedCount) {
        this.versionChangedCount = versionChangedCount;
    }

    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }

    public Instant getSourceCapturedAt() { return sourceCapturedAt; }
    public void setSourceCapturedAt(Instant sourceCapturedAt) {
        this.sourceCapturedAt = sourceCapturedAt;
    }

    public Instant getSourceCreatedAt() { return sourceCreatedAt; }
    public void setSourceCreatedAt(Instant sourceCreatedAt) {
        this.sourceCreatedAt = sourceCreatedAt;
    }

    public UUID getSourceRowId() { return sourceRowId; }
    public void setSourceRowId(UUID sourceRowId) { this.sourceRowId = sourceRowId; }

    public Instant getCreatedAt() { return createdAt; }
}
