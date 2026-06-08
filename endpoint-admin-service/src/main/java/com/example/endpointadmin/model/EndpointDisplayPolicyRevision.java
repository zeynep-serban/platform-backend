package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * #508 Endpoint Display Policy — append-only immutable revision history
 * (Faz 22.5 slice-2b; backs the V58 {@code endpoint_display_policy_revisions}
 * table). One row per PUT (ENFORCE) / DELETE (CLEAR) proposal; carries the full
 * desired-state snapshot (NULL for CLEAR), the normalized content hash, the
 * required reason, and the generated maker-checker command id.
 *
 * <p>Append-only: the V58 trigger {@code trg_edpr_appendonly} raises on any
 * UPDATE/DELETE, so there is no {@code @PreUpdate} and {@code orgId} is
 * canonicalized in {@link #prePersist()} only (mirrors
 * {@link EndpointDeviceLifecycleAudit}). The denormalised "current" desired
 * state lives in {@link EndpointDisplayPolicy}; this table is the history +
 * provenance link. Desired-state is distinct from enforcement-result: command
 * results update only {@code last_enforcement_*} on the current row, never a
 * revision.
 *
 * <p>org-composite: {@code (device_id, org_id)} FK to
 * {@code endpoint_devices(id, org_id)} and {@code (command_id, org_id)} FK to
 * {@code endpoint_commands(id, org_id)}; {@code org_id} filled to
 * {@code tenant_id} by the V58 compat trigger + canonicalized here so the
 * org-keyed arbiters hold on the H2 create-drop test path too.
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019ea911-b8fc-70e3-83bb-2b7e512114bd} (RED→REVISE→absorbed).
 */
@Entity
@Table(name = "endpoint_display_policy_revisions",
        indexes = {
                @Index(name = "idx_edpr_org_device_created",
                        columnList = "org_id,device_id,created_at DESC")
        })
public class EndpointDisplayPolicyRevision {

    /** Only DEVICE scope in v1 (GROUP deferred until a canonical group parent exists). */
    public static final String SCOPE_DEVICE = "DEVICE";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * org_id compat field (Faz 21.1 Option A). Nullable in JPA; the V58 CHECK
     * {@code org_id IS NOT NULL} + {@code org_id = tenant_id} + the compat
     * trigger are the live enforcement. Canonicalized to {@code tenantId} in
     * {@link #prePersist()} only (append-only table).
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType = SCOPE_DEVICE;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    private DisplayPolicyOperation operation;

    // ── desired-state snapshot (NULL for CLEAR) ──────────────────────────────
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

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Column(name = "command_id")
    private UUID commandId;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (scopeType == null) {
            scopeType = SCOPE_DEVICE;
        }
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointDisplayPolicyRevision that = (EndpointDisplayPolicyRevision) o;
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
    /** Effective-org accessor: orgId fallback to tenantId for legacy rows. */
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public DisplayPolicyOperation getOperation() { return operation; }
    public void setOperation(DisplayPolicyOperation operation) { this.operation = operation; }
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
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID getCommandId() { return commandId; }
    public void setCommandId(UUID commandId) { this.commandId = commandId; }
    public String getCreatedBySubject() { return createdBySubject; }
    public void setCreatedBySubject(String v) { this.createdBySubject = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
