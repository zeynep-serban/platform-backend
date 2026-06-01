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
 * BE — append-only hotfix posture snapshot (Faz 22.5, AG-037 ingest).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that carried
 * a {@code details.inventory.hotfixPosture} block (opt-in). Mirrors the
 * BE-022/AG-036 V20 outdated-software append-only precedent + the V13/V17
 * composite-FK tenant-integrity pattern EXACTLY, but extends them with:
 *
 * <ul>
 *   <li>THREE child facet tables (installed hotfixes + pending updates +
 *       pendingByCategory rollup) plus ONE grand-child (pending → pendingKbs)
 *       composite-FK normalized — Codex 019e81fe iter-2 P1.5 (KB ids
 *       normalized off JSONB so fleet/compliance KB-bazlı queries can
 *       index).</li>
 *   <li>Flat agent-health scalars at this snapshot root (no 1:1 child table —
 *       Codex 019e81fe iter-2 P1.3 — eliminates the
 *       {@code @OneToOne(fetch=LAZY)} fetch hazard that historically falls
 *       back to eager on Hibernate 6 + PG).</li>
 *   <li>BOTH a partial UNIQUE on {@code source_command_result_id} AND a full
 *       UNIQUE on {@code (tenant_id, device_id, payload_hash_sha256)} so the
 *       canonical-form payload-hash idempotency contract is enforced at the
 *       DB at the same physical layer as the source idempotency — the service
 *       targetless {@code INSERT ... ON CONFLICT DO NOTHING} write path
 *       race-cleanly catches both (Codex 019e81fe iter-3 P1.1).</li>
 * </ul>
 *
 * <h3>Redaction boundary (security invariant — DO NOT widen)</h3>
 *
 * <p>The per-installed-hotfix wire shape is EXACTLY
 * {@code {kbId, installedOn, description}}. The per-pending-update wire
 * shape is EXACTLY {@code {kbIds, primaryCategory, severity}} — NO raw
 * update title is on the wire in v1 (operator-visible noise + leak vector;
 * the {@code kbIds} correlation handle plus {@code primaryCategory} +
 * {@code severity} classification is enough for posture rendering). The
 * agent-health scalars are bounded to seven fields
 * {@code {wuaServiceState, bitsServiceState, lastDetectAt, lastInstallAt,
 * autoUpdatePolicyEnabled, autoUpdateEffectiveEnabled, notificationLevel}}.
 *
 * <p>{@code HotfixPosturePayloadPolicy} fail-closed rejects forbidden
 * Microsoft-update fields ({@code productCode}, {@code msiGuid},
 * {@code supersedence}, {@code installClient}, {@code installedBy},
 * {@code commandLine}, {@code accountName}, {@code title},
 * {@code clientApplicationId}, {@code deploymentAction}, {@code rawOutput})
 * BEFORE the parent command-result row is persisted. {@code probeError.summary}
 * is bounded operator text (no raw HRESULT errno / path / KB title).
 *
 * <h3>Read-only boundary</h3>
 *
 * <p>The underlying probe is read-only (pinned PowerShell + WUA COM
 * {@code Microsoft.Update.Session.CreateUpdateSearcher.Search/QueryHistory}
 * + {@code Get-CimInstance Win32_Service} + AU policy registry reads +
 * {@code Get-HotFix} installed-only fallback) — it NEVER mutates Windows
 * Update state. Persisting this snapshot never triggers any agent-side
 * mutation.
 *
 * <h3>Append-only with hash-idempotent replay</h3>
 *
 * <p>Each successful ingest either appends a NEW row (new posture hash) or
 * returns the existing snapshot row for the same
 * {@code (tenant, device, payload_hash)} (identical posture). The HARD
 * UNIQUE on {@code (tenant_id, device_id, payload_hash_sha256)} race-protects
 * concurrent SUBMIT-result calls for the same payload; the service's
 * targetless {@code ON CONFLICT DO NOTHING} write makes that protection
 * transaction-clean. Mirrors V20 outdated-software secondary hash dedupe
 * semantics.
 *
 * <h3>Fail-closed evidence</h3>
 *
 * <p>{@code supported=false} (non-Windows runtime) and
 * {@code probeComplete=false} (any probeError or no-evidence run) are
 * persisted AS evidence. Consumers MUST NOT render an incomplete probe as
 * "fully patched / no pending updates". The "possibly truncated" rendering
 * hint is computed at the DTO / audit-event boundary via
 * {@link com.example.endpointadmin.service.HotfixPostureSnapshotTruncation}.
 */
@Entity
@Table(name = "endpoint_hotfix_posture_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_hotfix_post_snap_id_tnt",
                        columnNames = {"id", "tenant_id"}),
                @UniqueConstraint(
                        name = "uq_endpoint_hotfix_post_snap_tnt_dev_hash",
                        columnNames = {"tenant_id", "device_id", "payload_hash_sha256"})
        },
        indexes = {
                @Index(name = "idx_endpoint_hotfix_post_snap_tnt_dev_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointHotfixPostureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /** Pointer to the originating agent command-result; NULL for
     *  manual/test ingest paths. Partial UNIQUE at the DB layer
     *  ({@code WHERE source_command_result_id IS NOT NULL}). */
    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Short schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "probe_complete", nullable = false)
    private Boolean probeComplete;

    /** Pre-truncation total installed-hotfix count (mirror V20
     *  {@code upgradeCount} semantics — what the source claims existed).
     *  Persisted child row count is post-cap (<= 512). */
    @Column(name = "installed_count", nullable = false)
    private Integer installedCount;

    @Column(name = "max_installed", nullable = false)
    private Integer maxInstalled;

    @Column(name = "installed_truncated", nullable = false)
    private Boolean installedTruncated;

    /** Pre-truncation total pending-update count. NO upper CHECK relative
     *  to {@code maxPending} — the pendingByCategory rollup may legitimately
     *  exceed the per-item cap. */
    @Column(name = "pending_total_count", nullable = false)
    private Integer pendingTotalCount;

    @Column(name = "max_pending", nullable = false)
    private Integer maxPending;

    @Column(name = "pending_truncated", nullable = false)
    private Boolean pendingTruncated;

    /** {@code wua | getHotfix | none}. */
    @Column(name = "installed_source_used", nullable = false, length = 16)
    private String installedSourceUsed;

    /** {@code wua | none}. */
    @Column(name = "pending_source_used", nullable = false, length = 16)
    private String pendingSourceUsed;

    /** {@code service | registry | composite | none}. */
    @Column(name = "health_source_used", nullable = false, length = 16)
    private String healthSourceUsed;

    @Column(name = "probe_duration_ms")
    private Integer probeDurationMs;

    /** SHA-256 of the canonical-form policy-projected hotfix posture map.
     *  Stored as 64-char lowercase hex. EXCLUDES wire {@code collectedAt}
     *  and {@code probeDurationMs} (timing-only); INCLUDES
     *  {@code lastDetectAt} and {@code lastInstallAt} (posture evidence).
     *  Codex 019e81fe iter-3 ANSWER. */
    @Column(name = "payload_hash_sha256", nullable = false, length = 64)
    private String payloadHashSha256;

    // --- Flat agent-health scalars at snapshot root (Codex 019e81fe
    // iter-2 P1.3 — agentHealth 1:1 child eliminated). ---

    /** {@code RUNNING | STOPPED | DISABLED | UNKNOWN}. UNKNOWN is the
     *  fail-closed "could not read" sentinel (parity with the wire
     *  contract typed enum). */
    @Column(name = "wua_service_state", nullable = false, length = 8)
    private String wuaServiceState;

    @Column(name = "bits_service_state", nullable = false, length = 8)
    private String bitsServiceState;

    @Column(name = "last_detect_at")
    private Instant lastDetectAt;

    @Column(name = "last_install_at")
    private Instant lastInstallAt;

    /** 3-state nullable bool (wire *bool): TRUE / FALSE / NULL (registry
     *  unreadable). */
    @Column(name = "auto_update_policy_enabled")
    private Boolean autoUpdatePolicyEnabled;

    @Column(name = "auto_update_effective_enabled")
    private Boolean autoUpdateEffectiveEnabled;

    /** AUOptions registry value verbatim ('1'/'2'/'3'/'4' typical; some
     *  GPO variants emit '0' or padded values). Empty string is normalized
     *  to NULL by the policy BEFORE write. Bounded by the DB CHECK
     *  {@code ~ '^[0-9]{1,4}$'}. */
    @Column(name = "notification_level", length = 4)
    private String notificationLevel;

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

    /** Child installed-hotfix facets (LAZY). {@code @OrderBy} pins the
     *  deterministic replay order to the {@code rowOrdinal} the agent
     *  emitted (Codex 019e822b P2 follow-up). */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointHotfixPostureInstalled> installedHotfixes = new ArrayList<>();

    /** Child pending-update facets (LAZY). Each pending row has its OWN
     *  LAZY {@code kbs} grand-child collection — kept LAZY for v1 (latest
     *  fetch returns ONE snapshot; N+1 is acceptable per Codex 019e81fe
     *  iter-3 residual notes). {@code @OrderBy} pins replay order. */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointHotfixPosturePending> pendingUpdates = new ArrayList<>();

    /** Child pendingByCategory rollup (LAZY). Preserves the FULL
     *  pre-truncation category distribution even when the per-item pending
     *  list is capped (Codex 019e81fe iter-2 P1.1). */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointHotfixPosturePendingCategoryCount> pendingByCategory = new ArrayList<>();

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

    // ------------------------------------------------------------------
    // Getters/setters (canonical javabean)
    // ------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

    public UUID getSourceCommandResultId() { return sourceCommandResultId; }
    public void setSourceCommandResultId(UUID sourceCommandResultId) {
        this.sourceCommandResultId = sourceCommandResultId;
    }

    public Short getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Short schemaVersion) { this.schemaVersion = schemaVersion; }

    public Boolean getSupported() { return supported; }
    public void setSupported(Boolean supported) { this.supported = supported; }

    public Boolean getProbeComplete() { return probeComplete; }
    public void setProbeComplete(Boolean probeComplete) { this.probeComplete = probeComplete; }

    public Integer getInstalledCount() { return installedCount; }
    public void setInstalledCount(Integer installedCount) { this.installedCount = installedCount; }

    public Integer getMaxInstalled() { return maxInstalled; }
    public void setMaxInstalled(Integer maxInstalled) { this.maxInstalled = maxInstalled; }

    public Boolean getInstalledTruncated() { return installedTruncated; }
    public void setInstalledTruncated(Boolean installedTruncated) {
        this.installedTruncated = installedTruncated;
    }

    public Integer getPendingTotalCount() { return pendingTotalCount; }
    public void setPendingTotalCount(Integer pendingTotalCount) {
        this.pendingTotalCount = pendingTotalCount;
    }

    public Integer getMaxPending() { return maxPending; }
    public void setMaxPending(Integer maxPending) { this.maxPending = maxPending; }

    public Boolean getPendingTruncated() { return pendingTruncated; }
    public void setPendingTruncated(Boolean pendingTruncated) {
        this.pendingTruncated = pendingTruncated;
    }

    public String getInstalledSourceUsed() { return installedSourceUsed; }
    public void setInstalledSourceUsed(String installedSourceUsed) {
        this.installedSourceUsed = installedSourceUsed;
    }

    public String getPendingSourceUsed() { return pendingSourceUsed; }
    public void setPendingSourceUsed(String pendingSourceUsed) {
        this.pendingSourceUsed = pendingSourceUsed;
    }

    public String getHealthSourceUsed() { return healthSourceUsed; }
    public void setHealthSourceUsed(String healthSourceUsed) {
        this.healthSourceUsed = healthSourceUsed;
    }

    public Integer getProbeDurationMs() { return probeDurationMs; }
    public void setProbeDurationMs(Integer probeDurationMs) { this.probeDurationMs = probeDurationMs; }

    public String getPayloadHashSha256() { return payloadHashSha256; }
    public void setPayloadHashSha256(String payloadHashSha256) {
        this.payloadHashSha256 = payloadHashSha256;
    }

    public String getWuaServiceState() { return wuaServiceState; }
    public void setWuaServiceState(String wuaServiceState) { this.wuaServiceState = wuaServiceState; }

    public String getBitsServiceState() { return bitsServiceState; }
    public void setBitsServiceState(String bitsServiceState) { this.bitsServiceState = bitsServiceState; }

    public Instant getLastDetectAt() { return lastDetectAt; }
    public void setLastDetectAt(Instant lastDetectAt) { this.lastDetectAt = lastDetectAt; }

    public Instant getLastInstallAt() { return lastInstallAt; }
    public void setLastInstallAt(Instant lastInstallAt) { this.lastInstallAt = lastInstallAt; }

    public Boolean getAutoUpdatePolicyEnabled() { return autoUpdatePolicyEnabled; }
    public void setAutoUpdatePolicyEnabled(Boolean v) { this.autoUpdatePolicyEnabled = v; }

    public Boolean getAutoUpdateEffectiveEnabled() { return autoUpdateEffectiveEnabled; }
    public void setAutoUpdateEffectiveEnabled(Boolean v) { this.autoUpdateEffectiveEnabled = v; }

    public String getNotificationLevel() { return notificationLevel; }
    public void setNotificationLevel(String notificationLevel) {
        this.notificationLevel = notificationLevel;
    }

    public Map<String, Object> getRedactedPayload() { return redactedPayload; }
    public void setRedactedPayload(Map<String, Object> redactedPayload) {
        this.redactedPayload = redactedPayload == null ? new HashMap<>() : redactedPayload;
    }

    public List<Map<String, Object>> getProbeErrors() { return probeErrors; }
    public void setProbeErrors(List<Map<String, Object>> probeErrors) {
        this.probeErrors = probeErrors == null ? new ArrayList<>() : probeErrors;
    }

    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Long getVersion() { return version; }

    public List<EndpointHotfixPostureInstalled> getInstalledHotfixes() {
        return installedHotfixes;
    }
    public void setInstalledHotfixes(List<EndpointHotfixPostureInstalled> v) {
        this.installedHotfixes = v == null ? new ArrayList<>() : v;
    }

    public List<EndpointHotfixPosturePending> getPendingUpdates() { return pendingUpdates; }
    public void setPendingUpdates(List<EndpointHotfixPosturePending> v) {
        this.pendingUpdates = v == null ? new ArrayList<>() : v;
    }

    public List<EndpointHotfixPosturePendingCategoryCount> getPendingByCategory() {
        return pendingByCategory;
    }
    public void setPendingByCategory(List<EndpointHotfixPosturePendingCategoryCount> v) {
        this.pendingByCategory = v == null ? new ArrayList<>() : v;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHotfixPostureSnapshot that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
