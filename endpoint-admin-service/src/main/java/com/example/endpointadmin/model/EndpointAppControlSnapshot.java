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
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BE — append-only Application Control (WDAC + AppLocker) snapshot
 * (Faz 22.5, AG-041-be). Mirrors AG-039/AG-040 2-table composite-FK
 * precedent. Wire shape carries 20 top-level keys (Codex 019e840e plan
 * iter-2 AGREE absorb #1); no per-rule list child table because the
 * agent contract HARD BOUNDARY forbids persisting AppLocker rule bodies
 * / WDAC policy names / publishers / file paths.
 *
 * <h3>Nullable evidence semantics</h3>
 *
 * <p>The 4 WDAC evidence columns + AppIDSvc present column are NULLABLE
 * to preserve the wire-contract distinction between `null` (not
 * queryable / not yet observed) and `false`. Sentinel values are
 * FORBIDDEN (Codex 019e840e plan iter-1 must_fix #5).
 *
 * <h3>Canonical-form payload hash</h3>
 *
 * <p>INCLUDES every persistable scalar (including null evidence values
 * preserved literally as Jackson `null` in the projection map) + ordered
 * probeErrors. EXCLUDES: none. Each fresh observation appends a new
 * snapshot.
 *
 * <h3>Idempotency</h3>
 *
 * <p>Dual UNIQUE: partial UNIQUE source_command_result_id + full UNIQUE
 * (tenant_id, device_id, payload_hash_sha256). Targetless ON CONFLICT
 * DO NOTHING catches both legs race-cleanly.
 */
@Entity
@Table(name = "endpoint_app_control_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_ac_snap_id_tnt",
                        columnNames = {"id", "tenant_id"}),
                @UniqueConstraint(
                        name = "uq_endpoint_ac_snap_tnt_dev_hash",
                        columnNames = {"tenant_id", "device_id", "payload_hash_sha256"})
        },
        indexes = {
                @Index(name = "idx_endpoint_ac_snap_tnt_dev_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointAppControlSnapshot {

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

    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "probe_complete", nullable = false)
    private Boolean probeComplete;

    @Column(name = "wdac_queryable", nullable = false)
    private Boolean wdacQueryable;

    @Column(name = "app_locker_queryable", nullable = false)
    private Boolean appLockerQueryable;

    @Column(name = "wdac_mode", nullable = false, length = 16)
    private String wdacMode;

    // NULLABLE WDAC evidence — Codex iter-1 must_fix #5 absorb.
    @Column(name = "wdac_boot_enforcement_present")
    private Boolean wdacBootEnforcementPresent;

    @Column(name = "wdac_active_cip_policy_count")
    private Integer wdacActiveCipPolicyCount;

    @Column(name = "wdac_legacy_sipolicy_present")
    private Boolean wdacLegacySipolicyPresent;

    @Column(name = "wdac_multi_policy_mode")
    private Boolean wdacMultiPolicyMode;

    // 5 AppLocker per-collection enforcement enums.
    @Column(name = "app_locker_exe_rule", nullable = false, length = 16)
    private String appLockerExeRule;

    @Column(name = "app_locker_dll_rule", nullable = false, length = 16)
    private String appLockerDllRule;

    @Column(name = "app_locker_script_rule", nullable = false, length = 16)
    private String appLockerScriptRule;

    @Column(name = "app_locker_msi_rule", nullable = false, length = 16)
    private String appLockerMsiRule;

    @Column(name = "app_locker_appx_rule", nullable = false, length = 16)
    private String appLockerAppxRule;

    // AppIDSvc state + startup + present (reusing V24 endpoint_services
    // enum surfaces — Codex iter-2 absorb #4).
    @Column(name = "app_locker_app_id_svc_state", nullable = false, length = 16)
    private String appLockerAppIdSvcState;

    @Column(name = "app_locker_app_id_svc_startup", nullable = false, length = 16)
    private String appLockerAppIdSvcStartup;

    @Column(name = "app_locker_app_id_svc_present")
    private Boolean appLockerAppIdSvcPresent;

    @Column(name = "probe_duration_ms", nullable = false)
    private Integer probeDurationMs;

    @Column(name = "payload_hash_sha256", nullable = false, length = 64)
    private String payloadHashSha256;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointAppControlProbeError> probeErrors = new ArrayList<>();

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    /** Faz 21.1 PR2b-i org_id accessor (Codex 019e8cac Option A). */
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    /** Faz 21.1 PR2b-i effective-org accessor: orgId fallback to tenantId. */
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getSourceCommandResultId() { return sourceCommandResultId; }
    public void setSourceCommandResultId(UUID sourceCommandResultId) { this.sourceCommandResultId = sourceCommandResultId; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public Boolean getSupported() { return supported; }
    public void setSupported(Boolean supported) { this.supported = supported; }
    public Boolean getProbeComplete() { return probeComplete; }
    public void setProbeComplete(Boolean probeComplete) { this.probeComplete = probeComplete; }
    public Boolean getWdacQueryable() { return wdacQueryable; }
    public void setWdacQueryable(Boolean wdacQueryable) { this.wdacQueryable = wdacQueryable; }
    public Boolean getAppLockerQueryable() { return appLockerQueryable; }
    public void setAppLockerQueryable(Boolean appLockerQueryable) { this.appLockerQueryable = appLockerQueryable; }
    public String getWdacMode() { return wdacMode; }
    public void setWdacMode(String wdacMode) { this.wdacMode = wdacMode; }
    public Boolean getWdacBootEnforcementPresent() { return wdacBootEnforcementPresent; }
    public void setWdacBootEnforcementPresent(Boolean v) { this.wdacBootEnforcementPresent = v; }
    public Integer getWdacActiveCipPolicyCount() { return wdacActiveCipPolicyCount; }
    public void setWdacActiveCipPolicyCount(Integer v) { this.wdacActiveCipPolicyCount = v; }
    public Boolean getWdacLegacySipolicyPresent() { return wdacLegacySipolicyPresent; }
    public void setWdacLegacySipolicyPresent(Boolean v) { this.wdacLegacySipolicyPresent = v; }
    public Boolean getWdacMultiPolicyMode() { return wdacMultiPolicyMode; }
    public void setWdacMultiPolicyMode(Boolean v) { this.wdacMultiPolicyMode = v; }
    public String getAppLockerExeRule() { return appLockerExeRule; }
    public void setAppLockerExeRule(String v) { this.appLockerExeRule = v; }
    public String getAppLockerDllRule() { return appLockerDllRule; }
    public void setAppLockerDllRule(String v) { this.appLockerDllRule = v; }
    public String getAppLockerScriptRule() { return appLockerScriptRule; }
    public void setAppLockerScriptRule(String v) { this.appLockerScriptRule = v; }
    public String getAppLockerMsiRule() { return appLockerMsiRule; }
    public void setAppLockerMsiRule(String v) { this.appLockerMsiRule = v; }
    public String getAppLockerAppxRule() { return appLockerAppxRule; }
    public void setAppLockerAppxRule(String v) { this.appLockerAppxRule = v; }
    public String getAppLockerAppIdSvcState() { return appLockerAppIdSvcState; }
    public void setAppLockerAppIdSvcState(String v) { this.appLockerAppIdSvcState = v; }
    public String getAppLockerAppIdSvcStartup() { return appLockerAppIdSvcStartup; }
    public void setAppLockerAppIdSvcStartup(String v) { this.appLockerAppIdSvcStartup = v; }
    public Boolean getAppLockerAppIdSvcPresent() { return appLockerAppIdSvcPresent; }
    public void setAppLockerAppIdSvcPresent(Boolean v) { this.appLockerAppIdSvcPresent = v; }
    public Integer getProbeDurationMs() { return probeDurationMs; }
    public void setProbeDurationMs(Integer probeDurationMs) { this.probeDurationMs = probeDurationMs; }
    public String getPayloadHashSha256() { return payloadHashSha256; }
    public void setPayloadHashSha256(String payloadHashSha256) { this.payloadHashSha256 = payloadHashSha256; }
    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<EndpointAppControlProbeError> getProbeErrors() { return probeErrors; }
    public void setProbeErrors(List<EndpointAppControlProbeError> probeErrors) { this.probeErrors = probeErrors; }
}
