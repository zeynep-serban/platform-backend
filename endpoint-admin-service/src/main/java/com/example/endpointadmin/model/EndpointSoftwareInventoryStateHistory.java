package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-024 — append-only per-device software-state capture (Faz 22.5).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that carried
 * a <em>full</em> {@code apps[]} software payload. The BE-020I
 * {@link EndpointSoftwareInventorySnapshot} read model is a single-row
 * upsert that physically deletes the prior items on every full re-collect,
 * so it cannot answer "what changed since last time". This history table
 * retains the diff-relevant subset of each full capture so the diff service
 * can compute added / removed / version-changed apps between the latest two
 * captures (mirrors the BE-022 hardware-inventory V13 + device-health V17
 * append-only precedent).
 *
 * <p>Written inline inside
 * {@link com.example.endpointadmin.service.EndpointSoftwareInventoryService#ingest}
 * in the SAME transaction as the snapshot upsert — only on a full apps[]
 * payload. Summary-only / wingetEgress-only ingests do NOT append (the app
 * state did not change). The existing snapshot upsert behaviour is unchanged
 * (purely additive).
 *
 * <p>{@code appsDigest} carries ONLY the whitelist subset per app
 * ({@code appKey}, {@code displayName}, {@code publisher}, {@code version},
 * {@code msiProductCodeHash}) — a strict subset of the already-sanitized
 * {@link EndpointSoftwareInventoryItem} fields. No user path / uninstall
 * string / raw GUID can reach this column (the
 * {@code SoftwareInventoryPayloadPolicy} fail-closed validator already
 * rejected those before the parent result row was persisted).
 *
 * <p>{@code appKey} is a SYNTHETIC stable identity (SHA-256 over
 * {@code lower(displayName)|lower(publisher)|msiProductCodeHash}); it is NOT
 * the winget catalog packageId (inventory items have no packageId column).
 *
 * <p>v1 retention is unbounded; a pruning/cap job is a deliberate follow-up.
 */
@Entity
@Table(name = "endpoint_software_inventory_state_history",
        indexes = {
                @Index(
                        // "device" abbreviated to "dev": the full name is 64
                        // bytes, over PostgreSQL's 63-byte identifier limit
                        // (would be truncated by PG and diverge from the V18
                        // CREATE INDEX name). Keep this in lockstep with V18.
                        name = "idx_endpoint_software_inventory_state_history_tenant_dev_time",
                        columnList = "tenant_id,device_id,captured_at,created_at,id")
        })
public class EndpointSoftwareInventoryStateHistory {

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
     *  manual/test ingest paths. Partial UNIQUE at the DB layer so the
     *  agent SUBMIT path is idempotent (1 capture per result). */
    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "app_count", nullable = false)
    private Integer appCount;

    /** Deterministic SHA-256 over the canonical {@code appsDigest} content
     *  (64 lowercase hex). Equal across byte-identical re-collects. */
    @Column(name = "apps_digest_hash", nullable = false, length = 64)
    private String appsDigestHash;

    /**
     * Whitelist-only per-app digest: a list of maps, each carrying
     * {@code {appKey, displayName, publisher, version, msiProductCodeHash}}.
     * Diff-relevant fields only.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "apps_digest", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> appsDigest = new ArrayList<>();

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (appsDigest == null) {
            appsDigest = new ArrayList<>();
        }
        if (appCount == null) {
            appCount = appsDigest.size();
        }
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

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Integer getAppCount() {
        return appCount;
    }

    public void setAppCount(Integer appCount) {
        this.appCount = appCount;
    }

    public String getAppsDigestHash() {
        return appsDigestHash;
    }

    public void setAppsDigestHash(String appsDigestHash) {
        this.appsDigestHash = appsDigestHash;
    }

    public List<Map<String, Object>> getAppsDigest() {
        return appsDigest;
    }

    public void setAppsDigest(List<Map<String, Object>> appsDigest) {
        this.appsDigest = appsDigest == null ? new ArrayList<>() : appsDigest;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointSoftwareInventoryStateHistory that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
