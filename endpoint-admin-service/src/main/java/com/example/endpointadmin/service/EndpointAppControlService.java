package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointAppControlProbeError;
import com.example.endpointadmin.model.EndpointAppControlSnapshot;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointAppControlSnapshotRepository;
import com.example.endpointadmin.security.AppControlPayloadPolicy;
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
 * BE — Application Control (WDAC + AppLocker) inventory ingest + query
 * service (Faz 22.5, AG-041-be). Mirrors AG-040-be {@link
 * EndpointStartupExposureService}. Dual-winner double-lookup invariant
 * + canonical-form hash + retry-idempotent dedupe.
 */
@Service
public class EndpointAppControlService {

    private static final Logger log = LoggerFactory.getLogger(EndpointAppControlService.class);

    private final EndpointAppControlSnapshotRepository repository;
    private final AppControlPayloadPolicy policy;

    public EndpointAppControlService(
            EndpointAppControlSnapshotRepository repository,
            AppControlPayloadPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    public static boolean hasAppControlBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("appControl") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("appControl") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointAppControlSnapshot ingest(
            EndpointDevice device,
            EndpointCommand command,
            EndpointCommandResult result,
            Map<String, Object> effectiveDetails) {

        if (device == null) {
            throw new IllegalArgumentException("device required");
        }
        UUID commandResultId = result != null ? result.getId() : null;

        if (commandResultId != null) {
            Optional<EndpointAppControlSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                // Codex 019e83a8 iter-1 P2#6 absorb pattern: tenant/device
                // cross-check on early short-circuit too — defends against
                // a reused source_command_result_id whose snapshot belongs
                // to a different tenant/device.
                EndpointAppControlSnapshot bs = existing.get();
                if (!bs.getTenantId().equals(device.getTenantId())
                        || !bs.getDeviceId().equals(device.getId())) {
                    throw new IllegalStateException(
                            "app-control source command-result tenant/device mismatch on early lookup: snapshot "
                                    + bs.getId() + " (tenant=" + bs.getTenantId()
                                    + ", device=" + bs.getDeviceId() + ") vs request (tenant="
                                    + device.getTenantId() + ", device=" + device.getId() + ")");
                }
                log.debug("App-control ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return bs;
            }
        }

        Map<String, Object> appControl = extractAppControl(effectiveDetails);
        if (appControl == null) {
            throw new IllegalStateException(
                    "ingest called without an appControl block — hook should check"
                            + " hasAppControlBlock() first");
        }

        AppControlPayloadPolicy.Projection projection = policy.projectAndHash(appControl);
        String payloadHash = projection.payloadHashSha256();

        Optional<EndpointAppControlSnapshot> identical =
                repository.findByTenantDeviceAndPayloadHash(
                        device.getTenantId(), device.getId(), payloadHash,
                        PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("App-control ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();

        EndpointAppControlSnapshot snapshot = buildSnapshot(
                device, commandResultId, projection, payloadHash, collectedAt);

        UUID insertedId = repository.insertAppControlSnapshotOnConflictDoNothing(snapshot);
        if (insertedId == null) {
            Optional<EndpointAppControlSnapshot> bySource = commandResultId == null
                    ? Optional.empty()
                    : repository.findBySourceCommandResultId(commandResultId);
            Optional<EndpointAppControlSnapshot> byHash =
                    repository.findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                            device.getTenantId(), device.getId(), payloadHash);
            if (bySource.isPresent() && byHash.isPresent()
                    && !bySource.get().getId().equals(byHash.get().getId())) {
                throw new IllegalStateException(
                        "app-control dual-winner invariant breach: source row "
                                + bySource.get().getId() + " != hash row "
                                + byHash.get().getId());
            }
            // Source row tenant/device cross-check.
            if (bySource.isPresent()) {
                EndpointAppControlSnapshot bs = bySource.get();
                if (!bs.getTenantId().equals(device.getTenantId())
                        || !bs.getDeviceId().equals(device.getId())) {
                    throw new IllegalStateException(
                            "app-control source command-result tenant/device mismatch: snapshot "
                                    + bs.getId() + " (tenant=" + bs.getTenantId()
                                    + ", device=" + bs.getDeviceId() + ") vs request (tenant="
                                    + device.getTenantId() + ", device=" + device.getId() + ")");
                }
                log.debug("App-control ingest no-op for command_result_id={} (lost source race)",
                        commandResultId);
                return bs;
            }
            if (byHash.isPresent()) {
                log.debug("App-control ingest no-op for device_id={} (lost hash race, snapshot_id={})",
                        device.getId(), byHash.get().getId());
                return byHash.get();
            }
            throw new IllegalStateException(
                    "app-control insert no-op without resolvable winner");
        }
        return snapshot;
    }

    public Optional<EndpointAppControlSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    private EndpointAppControlSnapshot buildSnapshot(
            EndpointDevice device,
            UUID commandResultId,
            AppControlPayloadPolicy.Projection p,
            String payloadHash,
            Instant collectedAt) {
        EndpointAppControlSnapshot snapshot = new EndpointAppControlSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(p.schemaVersion());
        snapshot.setSupported(p.supported());
        snapshot.setProbeComplete(p.probeComplete());
        snapshot.setWdacQueryable(p.wdacQueryable());
        snapshot.setAppLockerQueryable(p.appLockerQueryable());
        snapshot.setWdacMode(p.wdacMode());
        snapshot.setWdacBootEnforcementPresent(p.wdacBootEnforcementPresent());
        snapshot.setWdacActiveCipPolicyCount(p.wdacActiveCipPolicyCount());
        snapshot.setWdacLegacySipolicyPresent(p.wdacLegacySipolicyPresent());
        snapshot.setWdacMultiPolicyMode(p.wdacMultiPolicyMode());
        snapshot.setAppLockerExeRule(p.appLockerExeRule());
        snapshot.setAppLockerDllRule(p.appLockerDllRule());
        snapshot.setAppLockerScriptRule(p.appLockerScriptRule());
        snapshot.setAppLockerMsiRule(p.appLockerMsiRule());
        snapshot.setAppLockerAppxRule(p.appLockerAppxRule());
        snapshot.setAppLockerAppIdSvcState(p.appLockerAppIdSvcState());
        snapshot.setAppLockerAppIdSvcStartup(p.appLockerAppIdSvcStartup());
        snapshot.setAppLockerAppIdSvcPresent(p.appLockerAppIdSvcPresent());
        snapshot.setProbeDurationMs(p.probeDurationMs());
        snapshot.setPayloadHashSha256(payloadHash);
        snapshot.setCollectedAt(collectedAt);

        List<EndpointAppControlProbeError> errorEntities = new ArrayList<>(p.probeErrors().size());
        for (AppControlPayloadPolicy.ProbeErrorProjection pe : p.probeErrors()) {
            EndpointAppControlProbeError errEnt = new EndpointAppControlProbeError();
            errEnt.setRowOrdinal(pe.rowOrdinal());
            errEnt.setCode(pe.code());
            errEnt.setSource(pe.source());
            errEnt.setSummary(pe.summary());
            errEnt.setTenantId(device.getTenantId());
            errEnt.setSnapshot(snapshot);
            errorEntities.add(errEnt);
        }
        snapshot.setProbeErrors(errorEntities);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAppControl(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("appControl") instanceof Map<?, ?> s) {
            return (Map<String, Object>) s;
        }
        if (effectiveDetails.get("appControl") instanceof Map<?, ?> s2) {
            return (Map<String, Object>) s2;
        }
        return null;
    }
}
