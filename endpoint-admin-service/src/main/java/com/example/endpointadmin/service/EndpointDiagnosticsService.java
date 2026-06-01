package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDiagnosticsProbeError;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.repository.EndpointDiagnosticsSnapshotRepository;
import com.example.endpointadmin.security.DiagnosticsPayloadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — agent self-diagnostics ingest + query service (Faz 22.5, AG-038-be).
 * Mirrors the AG-037 {@link EndpointHotfixPostureService} pattern + the
 * Codex 019e82d7 iter-2 10-guard absorb.
 *
 * <h3>Dual idempotency</h3>
 *
 * <p>Targetless {@code ON CONFLICT DO NOTHING} + sequential winner lookup
 * (source FIRST, THEN hash; NOT mutually exclusive). Fail loud when
 * neither lookup resolves the winner (Codex 019e82d7 iter-2 #9).
 *
 * <h3>Canonical-form payload hash scope (Codex 019e82d7 iter-3 P1 #4 revise)</h3>
 *
 * <p>Computed by {@link DiagnosticsPayloadPolicy#projectAndHash(Map)}.
 * INCLUDES every persistable field: schemaVersion, supported,
 * probeComplete, agentVersion, configHash, lastPollLatencyMs,
 * probeDurationMs, backendDNSReachable, backendTLSValid, lastError
 * (UTC-normalized), probeErrors. EXCLUDES: none. Fresh observations
 * (different latency/duration) append new snapshots; identical
 * canonical bytes are retry-idempotent (same row returned).
 *
 * <h3>collectedAt provenance</h3>
 *
 * <p>Server-controlled: {@code EndpointCommandResult.reportedAt} (Codex
 * 019e82d7 iter-1 #4). The wire shape has no payload-level
 * {@code collectedAt}; if a payload key by that name appears it is
 * fail-closed REJECTED by the strict-allowlist policy.
 */
@Service
public class EndpointDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(EndpointDiagnosticsService.class);

    private final EndpointDiagnosticsSnapshotRepository repository;
    private final DiagnosticsPayloadPolicy policy;

    public EndpointDiagnosticsService(
            EndpointDiagnosticsSnapshotRepository repository,
            DiagnosticsPayloadPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    /** Predicate the agent SUBMIT-result hook uses to gate ingest. */
    public static boolean hasDiagnosticsBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("diagnostics") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("diagnostics") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointDiagnosticsSnapshot ingest(
            EndpointDevice device,
            EndpointCommand command,
            EndpointCommandResult result,
            Map<String, Object> effectiveDetails) {

        if (device == null) {
            throw new IllegalArgumentException("device required");
        }
        UUID commandResultId = result != null ? result.getId() : null;

        // Primary idempotency probe (source_command_result_id).
        if (commandResultId != null) {
            Optional<EndpointDiagnosticsSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Diagnostics ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> diagnostics = extractDiagnostics(effectiveDetails);
        if (diagnostics == null) {
            throw new IllegalStateException(
                    "ingest called without a diagnostics block — hook should check"
                            + " hasDiagnosticsBlock() first");
        }

        // Policy validation + canonical-form hash.
        DiagnosticsPayloadPolicy.Projection projection = policy.projectAndHash(diagnostics);
        String payloadHash = projection.payloadHashSha256();

        // Secondary payload-hash deep-equality dedupe pre-probe.
        Optional<EndpointDiagnosticsSnapshot> identical =
                repository.findByTenantDeviceAndPayloadHash(
                        device.getTenantId(), device.getId(), payloadHash,
                        PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Diagnostics ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();

        EndpointDiagnosticsSnapshot snapshot = buildSnapshot(
                device, commandResultId, projection, payloadHash, collectedAt);

        UUID insertedId = repository.insertDiagnosticsSnapshotOnConflictDoNothing(snapshot);
        if (insertedId == null) {
            // Targetless ON CONFLICT fired — Codex 019e82d7 iter-3 P2 #5
            // absorb: source AND hash lookups BOTH run, fail loud if they
            // resolve to different rows (impossible-invariant detector).
            Optional<EndpointDiagnosticsSnapshot> bySource = commandResultId == null
                    ? Optional.empty()
                    : repository.findBySourceCommandResultId(commandResultId);
            Optional<EndpointDiagnosticsSnapshot> byHash =
                    repository.findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                            device.getTenantId(), device.getId(), payloadHash);
            if (bySource.isPresent() && byHash.isPresent()
                    && !bySource.get().getId().equals(byHash.get().getId())) {
                // Different rows — should be physically impossible given
                // the partial+full UNIQUE pair on the table.
                throw new IllegalStateException(
                        "diagnostics dual-winner invariant breach: source row "
                                + bySource.get().getId() + " != hash row "
                                + byHash.get().getId());
            }
            if (bySource.isPresent()) {
                log.debug("Diagnostics ingest no-op for command_result_id={} (lost source race)",
                        commandResultId);
                return bySource.get();
            }
            if (byHash.isPresent()) {
                log.debug("Diagnostics ingest no-op for device_id={} (lost hash race, snapshot_id={})",
                        device.getId(), byHash.get().getId());
                return byHash.get();
            }
            // Codex 019e82d7 iter-2 #9: invariant breach, fail loud.
            throw new IllegalStateException(
                    "diagnostics insert no-op without resolvable winner");
        }
        return snapshot;
    }

    public Optional<EndpointDiagnosticsSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    private EndpointDiagnosticsSnapshot buildSnapshot(
            EndpointDevice device,
            UUID commandResultId,
            DiagnosticsPayloadPolicy.Projection p,
            String payloadHash,
            Instant collectedAt) {
        EndpointDiagnosticsSnapshot snapshot = new EndpointDiagnosticsSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(p.schemaVersion());
        snapshot.setSupported(p.supported());
        snapshot.setProbeComplete(p.probeComplete());
        snapshot.setAgentVersion(p.agentVersion());
        snapshot.setConfigHash(p.configHash());
        snapshot.setLastPollLatencyMs(p.lastPollLatencyMs());
        snapshot.setBackendDnsReachable(p.backendDnsReachable());
        snapshot.setBackendTlsValid(p.backendTlsValid());
        if (p.lastError() != null) {
            snapshot.setLastErrorOccurredAt(p.lastError().occurredAt());
            snapshot.setLastErrorCode(p.lastError().code());
            snapshot.setLastErrorSummary(p.lastError().summary());
        }
        snapshot.setProbeDurationMs(p.probeDurationMs());
        snapshot.setPayloadHashSha256(payloadHash);
        snapshot.setCollectedAt(collectedAt);

        List<EndpointDiagnosticsProbeError> errorEntities = new ArrayList<>(p.probeErrors().size());
        for (DiagnosticsPayloadPolicy.ProbeErrorProjection pe : p.probeErrors()) {
            EndpointDiagnosticsProbeError errEnt = new EndpointDiagnosticsProbeError();
            errEnt.setRowOrdinal(pe.rowOrdinal());
            errEnt.setCode(pe.code());
            errEnt.setSummary(pe.summary());
            errEnt.setTenantId(device.getTenantId());
            errEnt.setSnapshot(snapshot);
            errorEntities.add(errEnt);
        }
        snapshot.setProbeErrors(errorEntities);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDiagnostics(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("diagnostics") instanceof Map<?, ?> d) {
            return (Map<String, Object>) d;
        }
        if (effectiveDetails.get("diagnostics") instanceof Map<?, ?> d2) {
            return (Map<String, Object>) d2;
        }
        return null;
    }
}
