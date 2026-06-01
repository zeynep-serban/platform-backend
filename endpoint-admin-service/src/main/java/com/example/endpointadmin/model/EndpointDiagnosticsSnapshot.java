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
 * BE — append-only agent self-diagnostics snapshot (Faz 22.5, AG-038-be).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that carried
 * a {@code details.inventory.diagnostics} block (opt-in). Mirrors the
 * AG-037 V22 hotfix-posture composite-FK precedent + extends it with the
 * AG-038-specific flat {@code lastError} triad (Codex 019e82d7 iter-1 P1
 * AGREE: flat scalars, not 1:0..1 child).
 *
 * <h3>Redaction boundary (security invariant — DO NOT widen)</h3>
 *
 * <p>The wire shape carries NO credential / token / raw API URL / host. Only:
 * {@code agentVersion} (semver-like or "unknown"), {@code configHash}
 * (sha256(agentVersion+apiURL) one-way; never reversed),
 * {@code lastPollLatencyMs} (int), {@code backendDNSReachable /
 * backendTLSValid} (bool), {@code lastError {occurredAt, code, summary}}
 * optional + bounded ≤200 chars CRLF-stripped, {@code probeErrors[]} each
 * {code, summary?} bounded enum code + bounded text summary, and
 * {@code probeDurationMs} int (INCLUDED in canonical-form hash so a
 * fresh observation appends a new snapshot per Codex iter-3 P1 #4).
 * {@code DiagnosticsPayloadPolicy} fail-closed REJECTS forbidden
 * keys (raw apiURL, host, credentialId, token, apiKey, bearer,
 * authorization, cookie, session, secret, password) BEFORE persist.
 *
 * <h3>Append-only with hash-idempotent replay</h3>
 *
 * <p>Each successful ingest either appends a NEW row (new diagnostics hash)
 * or returns the existing row for the same
 * {@code (tenant, device, payload_hash)}. Targetless
 * {@code ON CONFLICT DO NOTHING} catches BOTH
 * {@code source_command_result_id} partial UNIQUE AND
 * {@code (tenant, device, payload_hash)} full UNIQUE legs race-cleanly.
 *
 * <h3>Canonical-form payload hash scope (Codex 019e82d7 iter-3 P1 #4 revise)</h3>
 *
 * <p>INCLUDED in canonical hash bytes (every persistable field):
 * {@code schemaVersion, supported, probeComplete, agentVersion, configHash,
 * lastPollLatencyMs, probeDurationMs, backendDNSReachable, backendTLSValid,
 * lastError (full triad with UTC-normalized occurredAt), probeErrors
 * (ordered list)}.
 *
 * <p>EXCLUDED: none. Each fresh observation appends a new snapshot and
 * {@code /latest} reflects the most recent measured latency/duration.
 * Iter-2's "exclude latency/duration" heuristic was reversed in iter-3
 * because it caused {@code /latest} staleness on state-A→B→A and
 * latency-only-change cases. Retry-idempotent replay of identical
 * canonical bytes is still de-duped via the pre-probe and the
 * {@code (tenant, device, hash)} UNIQUE.
 */
@Entity
@Table(name = "endpoint_diagnostics_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_diag_snap_id_tnt",
                        columnNames = {"id", "tenant_id"}),
                @UniqueConstraint(
                        name = "uq_endpoint_diag_snap_tnt_dev_hash",
                        columnNames = {"tenant_id", "device_id", "payload_hash_sha256"})
        },
        indexes = {
                @Index(name = "idx_endpoint_diag_snap_tnt_dev_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointDiagnosticsSnapshot {

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
    private Integer schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "probe_complete", nullable = false)
    private Boolean probeComplete;

    @Column(name = "agent_version", nullable = false, length = 64)
    private String agentVersion;

    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    @Column(name = "last_poll_latency_ms", nullable = false)
    private Integer lastPollLatencyMs;

    @Column(name = "backend_dns_reachable", nullable = false)
    private Boolean backendDnsReachable;

    @Column(name = "backend_tls_valid", nullable = false)
    private Boolean backendTlsValid;

    // --- lastError triad (flat scalars; nullable as a triad per V23 CHECK) ---

    @Column(name = "last_error_occurred_at")
    private Instant lastErrorOccurredAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_summary", length = 200)
    private String lastErrorSummary;

    @Column(name = "probe_duration_ms", nullable = false)
    private Integer probeDurationMs;

    /** SHA-256 of the canonical-form policy-projected diagnostics map.
     *  Stored as 64-char lowercase hex. INCLUDES every persistable field
     *  (schemaVersion, supported, probeComplete, agentVersion, configHash,
     *  lastPollLatencyMs, probeDurationMs, backendDNSReachable,
     *  backendTLSValid, lastError triad, probeErrors) per Codex iter-3
     *  P1 #4 revise. {@code lastError.occurredAt} is INCLUDED as
     *  operational evidence (per Codex 019e82d7 iter-1 #4 / iter-2 #2). */
    @Column(name = "payload_hash_sha256", nullable = false, length = 64)
    private String payloadHashSha256;

    /** Server-controlled (= {@code EndpointCommandResult.reportedAt}); NOT
     *  derived from any payload field (Codex 019e82d7 iter-1 #4). */
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Child probe-error facets (LAZY). {@code @OrderBy} pins the
     *  deterministic replay order to the {@code rowOrdinal} the agent
     *  emitted. */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointDiagnosticsProbeError> probeErrors = new ArrayList<>();

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // -- accessors --

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

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

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }

    public Integer getLastPollLatencyMs() { return lastPollLatencyMs; }
    public void setLastPollLatencyMs(Integer lastPollLatencyMs) { this.lastPollLatencyMs = lastPollLatencyMs; }

    public Boolean getBackendDnsReachable() { return backendDnsReachable; }
    public void setBackendDnsReachable(Boolean backendDnsReachable) { this.backendDnsReachable = backendDnsReachable; }

    public Boolean getBackendTlsValid() { return backendTlsValid; }
    public void setBackendTlsValid(Boolean backendTlsValid) { this.backendTlsValid = backendTlsValid; }

    public Instant getLastErrorOccurredAt() { return lastErrorOccurredAt; }
    public void setLastErrorOccurredAt(Instant lastErrorOccurredAt) { this.lastErrorOccurredAt = lastErrorOccurredAt; }

    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }

    public String getLastErrorSummary() { return lastErrorSummary; }
    public void setLastErrorSummary(String lastErrorSummary) { this.lastErrorSummary = lastErrorSummary; }

    public Integer getProbeDurationMs() { return probeDurationMs; }
    public void setProbeDurationMs(Integer probeDurationMs) { this.probeDurationMs = probeDurationMs; }

    public String getPayloadHashSha256() { return payloadHashSha256; }
    public void setPayloadHashSha256(String payloadHashSha256) { this.payloadHashSha256 = payloadHashSha256; }

    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<EndpointDiagnosticsProbeError> getProbeErrors() { return probeErrors; }
    public void setProbeErrors(List<EndpointDiagnosticsProbeError> probeErrors) { this.probeErrors = probeErrors; }
}
