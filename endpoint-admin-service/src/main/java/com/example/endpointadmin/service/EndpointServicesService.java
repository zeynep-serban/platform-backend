package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointServicesEntry;
import com.example.endpointadmin.model.EndpointServicesProbeError;
import com.example.endpointadmin.model.EndpointServicesSnapshot;
import com.example.endpointadmin.repository.EndpointServicesSnapshotRepository;
import com.example.endpointadmin.security.ServicesPayloadPolicy;
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
 * BE — critical services inventory ingest + query service (Faz 22.5,
 * AG-039-be). Mirrors AG-038-be {@link EndpointDiagnosticsService}.
 * Dual-winner double-lookup invariant + canonical-form hash + retry-
 * idempotent dedupe.
 */
@Service
public class EndpointServicesService {

    private static final Logger log = LoggerFactory.getLogger(EndpointServicesService.class);

    private final EndpointServicesSnapshotRepository repository;
    private final ServicesPayloadPolicy policy;

    public EndpointServicesService(
            EndpointServicesSnapshotRepository repository,
            ServicesPayloadPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    public static boolean hasServicesBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("services") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("services") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointServicesSnapshot ingest(
            EndpointDevice device,
            EndpointCommand command,
            EndpointCommandResult result,
            Map<String, Object> effectiveDetails) {

        if (device == null) {
            throw new IllegalArgumentException("device required");
        }
        UUID commandResultId = result != null ? result.getId() : null;

        if (commandResultId != null) {
            Optional<EndpointServicesSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Services ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> services = extractServices(effectiveDetails);
        if (services == null) {
            throw new IllegalStateException(
                    "ingest called without a services block — hook should check"
                            + " hasServicesBlock() first");
        }

        ServicesPayloadPolicy.Projection projection = policy.projectAndHash(services);
        String payloadHash = projection.payloadHashSha256();

        Optional<EndpointServicesSnapshot> identical =
                repository.findByTenantDeviceAndPayloadHash(
                        device.getTenantId(), device.getId(), payloadHash,
                        PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Services ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();

        EndpointServicesSnapshot snapshot = buildSnapshot(
                device, commandResultId, projection, payloadHash, collectedAt);

        UUID insertedId = repository.insertServicesSnapshotOnConflictDoNothing(snapshot);
        if (insertedId == null) {
            Optional<EndpointServicesSnapshot> bySource = commandResultId == null
                    ? Optional.empty()
                    : repository.findBySourceCommandResultId(commandResultId);
            Optional<EndpointServicesSnapshot> byHash =
                    repository.findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                            device.getTenantId(), device.getId(), payloadHash);
            if (bySource.isPresent() && byHash.isPresent()
                    && !bySource.get().getId().equals(byHash.get().getId())) {
                throw new IllegalStateException(
                        "services dual-winner invariant breach: source row "
                                + bySource.get().getId() + " != hash row "
                                + byHash.get().getId());
            }
            // Codex iter-2 #6 absorb: cross-check source row tenant/device match.
            if (bySource.isPresent()) {
                EndpointServicesSnapshot bs = bySource.get();
                if (!bs.getTenantId().equals(device.getTenantId())
                        || !bs.getDeviceId().equals(device.getId())) {
                    throw new IllegalStateException(
                            "services source command-result tenant/device mismatch: snapshot "
                                    + bs.getId() + " (tenant=" + bs.getTenantId()
                                    + ", device=" + bs.getDeviceId() + ") vs request (tenant="
                                    + device.getTenantId() + ", device=" + device.getId() + ")");
                }
                log.debug("Services ingest no-op for command_result_id={} (lost source race)",
                        commandResultId);
                return bs;
            }
            if (byHash.isPresent()) {
                log.debug("Services ingest no-op for device_id={} (lost hash race, snapshot_id={})",
                        device.getId(), byHash.get().getId());
                return byHash.get();
            }
            throw new IllegalStateException(
                    "services insert no-op without resolvable winner");
        }
        return snapshot;
    }

    public Optional<EndpointServicesSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    private EndpointServicesSnapshot buildSnapshot(
            EndpointDevice device,
            UUID commandResultId,
            ServicesPayloadPolicy.Projection p,
            String payloadHash,
            Instant collectedAt) {
        EndpointServicesSnapshot snapshot = new EndpointServicesSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(p.schemaVersion());
        snapshot.setSupported(p.supported());
        snapshot.setProbeComplete(p.probeComplete());
        snapshot.setProbeDurationMs(p.probeDurationMs());
        snapshot.setPayloadHashSha256(payloadHash);
        snapshot.setCollectedAt(collectedAt);

        List<EndpointServicesEntry> entryEntities = new ArrayList<>(p.services().size());
        for (ServicesPayloadPolicy.EntryProjection ep : p.services()) {
            EndpointServicesEntry e = new EndpointServicesEntry();
            e.setRowOrdinal(ep.rowOrdinal());
            e.setName(ep.name());
            e.setPresent(ep.present());
            e.setState(ep.state());
            e.setStartupMode(ep.startupMode());
            e.setTenantId(device.getTenantId());
            e.setSnapshot(snapshot);
            entryEntities.add(e);
        }
        snapshot.setServices(entryEntities);

        List<EndpointServicesProbeError> errorEntities = new ArrayList<>(p.probeErrors().size());
        for (ServicesPayloadPolicy.ProbeErrorProjection pe : p.probeErrors()) {
            EndpointServicesProbeError errEnt = new EndpointServicesProbeError();
            errEnt.setRowOrdinal(pe.rowOrdinal());
            errEnt.setCode(pe.code());
            errEnt.setServiceName(pe.serviceName());
            errEnt.setSummary(pe.summary());
            errEnt.setTenantId(device.getTenantId());
            errEnt.setSnapshot(snapshot);
            errorEntities.add(errEnt);
        }
        snapshot.setProbeErrors(errorEntities);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractServices(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("services") instanceof Map<?, ?> s) {
            return (Map<String, Object>) s;
        }
        if (effectiveDetails.get("services") instanceof Map<?, ?> s2) {
            return (Map<String, Object>) s2;
        }
        return null;
    }
}
