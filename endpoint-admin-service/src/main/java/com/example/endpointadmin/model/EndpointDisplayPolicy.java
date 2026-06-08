package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * #508 Endpoint Display Policy — current desired-state, one row per device
 * (Faz 22.5 slice-2b; backs the V58 {@code endpoint_display_policies} table).
 *
 * <p><b>Truth boundary (Codex 019ea911 RED-fix):</b> this row is the
 * <em>approved</em> desired-state, never a pending proposal. The PUT/DELETE
 * dispatch path writes ONLY a revision + a PENDING maker-checker command; this
 * current row is promoted from the backing revision ONLY when a second admin
 * APPROVES the command (via {@code DisplayPolicyApprovalListener} reacting to
 * the existing dual-control approve surface). A REJECTED proposal therefore
 * never becomes current truth.
 *
 * <p>{@code operation=ENFORCE} ⟺ {@code cleared_at IS NULL} (active managed
 * policy); {@code operation=CLEAR} ⟺ {@code cleared_at} set (managed keys
 * removed). The {@code ux_edp_active_device} partial unique guarantees at most
 * one active row per device. {@code last_enforcement_*} is the enforcement
 * RESULT (set by a later command-result slice), distinct from the desired
 * state.
 *
 * <p>org-composite: {@code (device_id, org_id)} FK to
 * {@code endpoint_devices(id, org_id)} and {@code (current_revision_id, org_id)}
 * FK to {@code endpoint_display_policy_revisions(id, org_id)}.
 */
@Entity
@Table(name = "endpoint_display_policies",
        indexes = {
                @Index(name = "idx_edp_org_device", columnList = "org_id,device_id")
        })
public class EndpointDisplayPolicy {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * org_id compat field (Faz 21.1 Option A). Canonicalized to {@code tenantId}
     * in {@link #prePersist()} / {@link #preUpdate()} so the org-keyed
     * {@code ux_edp_active_device} arbiter holds on the H2 create-drop path too.
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType = EndpointDisplayPolicyRevision.SCOPE_DEVICE;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    private DisplayPolicyOperation operation;

    @Column(name = "current_revision_id", nullable = false)
    private UUID currentRevisionId;

    // ── denormalised latest desired-state snapshot (NULL when CLEAR) ──────────
    @Column(name = "screensaver_enabled")
    private Boolean screensaverEnabled;

    @Column(name = "screensaver_timeout_seconds")
    private Integer screensaverTimeoutSeconds;

    @Column(name = "screensaver_secure")
    private Boolean screensaverSecure;

    @Column(name = "screensaver_scr_path", length = 260)
    private String screensaverScrPath;

    @Column(name = "wallpaper_enabled")
    private Boolean wallpaperEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallpaper_style", length = 16)
    private WallpaperStyle wallpaperStyle;

    @Column(name = "wallpaper_user_cannot_change")
    private Boolean wallpaperUserCannotChange;

    @Column(name = "wallpaper_asset_ref", length = 512)
    private String wallpaperAssetRef;

    @Column(name = "wallpaper_asset_sha256", length = 64)
    private String wallpaperAssetSha256;

    @Column(name = "wallpaper_content_type", length = 64)
    private String wallpaperContentType;

    @Column(name = "policy_hash_sha256", nullable = false, length = 64)
    private String policyHashSha256;

    // ── lifecycle: cleared_at IS NULL => active managed policy ────────────────
    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "cleared_by_subject", length = 255)
    private String clearedBySubject;

    // ── enforcement-result (separate from desired-state) ──────────────────────
    @Column(name = "last_enforcement_status", length = 24)
    private String lastEnforcementStatus;

    @Column(name = "last_enforced_at")
    private Instant lastEnforcedAt;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastUpdatedAt == null) {
            lastUpdatedAt = now;
        }
        if (scopeType == null) {
            scopeType = EndpointDisplayPolicyRevision.SCOPE_DEVICE;
        }
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    @PreUpdate
    void preUpdate() {
        lastUpdatedAt = Instant.now();
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    /** Clear all desired-state snapshot fields (used when flipping to CLEAR). */
    public void clearSnapshot() {
        this.screensaverEnabled = null;
        this.screensaverTimeoutSeconds = null;
        this.screensaverSecure = null;
        this.screensaverScrPath = null;
        this.wallpaperEnabled = null;
        this.wallpaperStyle = null;
        this.wallpaperUserCannotChange = null;
        this.wallpaperAssetRef = null;
        this.wallpaperAssetSha256 = null;
        this.wallpaperContentType = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointDisplayPolicy that = (EndpointDisplayPolicy) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public DisplayPolicyOperation getOperation() { return operation; }
    public void setOperation(DisplayPolicyOperation operation) { this.operation = operation; }
    public UUID getCurrentRevisionId() { return currentRevisionId; }
    public void setCurrentRevisionId(UUID v) { this.currentRevisionId = v; }
    public Boolean getScreensaverEnabled() { return screensaverEnabled; }
    public void setScreensaverEnabled(Boolean v) { this.screensaverEnabled = v; }
    public Integer getScreensaverTimeoutSeconds() { return screensaverTimeoutSeconds; }
    public void setScreensaverTimeoutSeconds(Integer v) { this.screensaverTimeoutSeconds = v; }
    public Boolean getScreensaverSecure() { return screensaverSecure; }
    public void setScreensaverSecure(Boolean v) { this.screensaverSecure = v; }
    public String getScreensaverScrPath() { return screensaverScrPath; }
    public void setScreensaverScrPath(String v) { this.screensaverScrPath = v; }
    public Boolean getWallpaperEnabled() { return wallpaperEnabled; }
    public void setWallpaperEnabled(Boolean v) { this.wallpaperEnabled = v; }
    public WallpaperStyle getWallpaperStyle() { return wallpaperStyle; }
    public void setWallpaperStyle(WallpaperStyle v) { this.wallpaperStyle = v; }
    public Boolean getWallpaperUserCannotChange() { return wallpaperUserCannotChange; }
    public void setWallpaperUserCannotChange(Boolean v) { this.wallpaperUserCannotChange = v; }
    public String getWallpaperAssetRef() { return wallpaperAssetRef; }
    public void setWallpaperAssetRef(String v) { this.wallpaperAssetRef = v; }
    public String getWallpaperAssetSha256() { return wallpaperAssetSha256; }
    public void setWallpaperAssetSha256(String v) { this.wallpaperAssetSha256 = v; }
    public String getWallpaperContentType() { return wallpaperContentType; }
    public void setWallpaperContentType(String v) { this.wallpaperContentType = v; }
    public String getPolicyHashSha256() { return policyHashSha256; }
    public void setPolicyHashSha256(String v) { this.policyHashSha256 = v; }
    public Instant getClearedAt() { return clearedAt; }
    public void setClearedAt(Instant v) { this.clearedAt = v; }
    public String getClearedBySubject() { return clearedBySubject; }
    public void setClearedBySubject(String v) { this.clearedBySubject = v; }
    public String getLastEnforcementStatus() { return lastEnforcementStatus; }
    public void setLastEnforcementStatus(String v) { this.lastEnforcementStatus = v; }
    public Instant getLastEnforcedAt() { return lastEnforcedAt; }
    public void setLastEnforcedAt(Instant v) { this.lastEnforcedAt = v; }
    public String getCreatedBySubject() { return createdBySubject; }
    public void setCreatedBySubject(String v) { this.createdBySubject = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getLastUpdatedBySubject() { return lastUpdatedBySubject; }
    public void setLastUpdatedBySubject(String v) { this.lastUpdatedBySubject = v; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant v) { this.lastUpdatedAt = v; }
    public Long getVersion() { return version; }
}
