package com.example.endpointadmin.service;

import com.example.endpointadmin.event.HardwareInventorySnapshotPersistedEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHardwareInventoryDisk;
import com.example.endpointadmin.model.EndpointHardwareInventoryNetworkInterface;
import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import com.example.endpointadmin.repository.EndpointHardwareInventorySnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-022 — hardware inventory service (Faz 22.5). Codex
 * {@code 019e7007} iter-4 absorb.
 *
 * <p>Caller contract: the agent SUBMIT-result hook in
 * {@link EndpointAgentCommandService} invokes
 * {@link #ingest(EndpointDevice, EndpointCommand, EndpointCommandResult, Map)}
 * after the command-result row has been persisted (with the sanitized
 * {@code effectiveDetails}). The hook only calls this method when the
 * sanitized {@code effectiveDetails} actually carries a
 * {@code details.inventory.hardware} block — see
 * {@link #hasHardwareBlock(Map)} for the canonical predicate.
 *
 * <p>Idempotency: the snapshot table has a partial UNIQUE on
 * {@code source_command_result_id}. The hook can safely re-deliver
 * the same command-result without creating a duplicate snapshot —
 * this service catches the DB-layer race
 * ({@link DataIntegrityViolationException}) and returns the existing
 * snapshot.
 *
 * <p>Append-only history: this service NEVER mutates a previous
 * snapshot. Every successful ingest produces a new row; the
 * {@code (tenant_id, device_id, collected_at DESC, created_at DESC,
 * id DESC)} composite index backs the latest-and-history queries.
 */
@Service
public class EndpointHardwareInventoryService {

    private static final Logger log = LoggerFactory.getLogger(EndpointHardwareInventoryService.class);

    private final EndpointHardwareInventorySnapshotRepository repository;
    private final ApplicationEventPublisher events;

    public EndpointHardwareInventoryService(
            EndpointHardwareInventorySnapshotRepository repository,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Check whether the sanitized {@code effectiveDetails} carries a
     * hardware sub-tree the hook should ingest.
     */
    public static boolean hasHardwareBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("hardware") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("hardware") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointHardwareInventorySnapshot ingest(
            EndpointDevice device,
            EndpointCommand command,
            EndpointCommandResult result,
            Map<String, Object> effectiveDetails) {

        if (device == null) {
            throw new IllegalArgumentException("device required");
        }
        UUID commandResultId = result != null ? result.getId() : null;

        // Idempotency probe.
        if (commandResultId != null) {
            Optional<EndpointHardwareInventorySnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Hardware ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> hardware = extractHardware(effectiveDetails);
        if (hardware == null) {
            throw new IllegalStateException(
                    "ingest called without a hardware block — hook should check hasHardwareBlock() first");
        }

        // BE-022Q payload-hash deep-equality dedupe probe (#327).
        // Secondary idempotency layer behind the source_command_result_id
        // probe above: when the agent re-collects byte-identical hardware
        // under a DIFFERENT command-result (so the first probe missed),
        // skip appending a duplicate row and return the existing latest
        // snapshot carrying the same hash. The hash is computed once here
        // and reused for the persisted entity so the probe and the stored
        // value can never diverge.
        String payloadHash = sha256Hex(hardware);
        Optional<EndpointHardwareInventorySnapshot> identical =
                repository
                        .findByTenantDeviceAndPayloadHash(
                                device.getTenantId(), device.getId(), payloadHash,
                                PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Hardware ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        EndpointHardwareInventorySnapshot snapshot = buildSnapshot(device, commandResultId, hardware, payloadHash);

        try {
            // Codex 019e7007 post-impl iter-1 must-fix #1: use
            // saveAndFlush so any partial-UNIQUE violation on
            // source_command_result_id surfaces NOW (and is caught
            // by the block below), rather than at transaction
            // commit when this catch is no longer in scope.
            EndpointHardwareInventorySnapshot persisted = repository.saveAndFlush(snapshot);
            log.info("Hardware inventory snapshot persisted device_id={} snapshot_id={} schema={}",
                    device.getId(), persisted.getId(), persisted.getSchemaVersion());
            events.publishEvent(buildAuditEvent(persisted, command));
            return persisted;
        } catch (DataIntegrityViolationException race) {
            // Defence-in-depth for concurrent SUBMIT-result deliveries.
            if (commandResultId != null) {
                return repository.findBySourceCommandResultId(commandResultId)
                        .orElseThrow(() -> race);
            }
            throw race;
        }
    }

    public Optional<EndpointHardwareInventorySnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    public Page<EndpointHardwareInventorySnapshot> findHistory(
            UUID tenantId, UUID deviceId, Pageable pageable) {
        return repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId, pageable);
    }

    // ------------------------------------------------------------------
    // Internals — buildSnapshot composes the entity from the sanitized
    // hardware sub-tree. Numeric values are parsed defensively; missing
    // optional facts persist as NULL.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractHardware(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("hardware") instanceof Map<?, ?> hw) {
            return (Map<String, Object>) hw;
        }
        Object topHardware = effectiveDetails.get("hardware");
        if (topHardware instanceof Map<?, ?> hw) {
            return (Map<String, Object>) hw;
        }
        return null;
    }

    private EndpointHardwareInventorySnapshot buildSnapshot(
            EndpointDevice device, UUID commandResultId, Map<String, Object> hardware,
            String payloadHash) {
        EndpointHardwareInventorySnapshot snapshot = new EndpointHardwareInventorySnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(intOr(hardware.get("schemaVersion"), 1));
        snapshot.setSupported(boolOr(hardware.get("supported"), Boolean.TRUE));

        snapshot.setCpuModel(strOr(hardware.get("cpuModel"), null));
        snapshot.setCpuCores(shortOr(hardware.get("cpuCores")));
        snapshot.setCpuFrequencyMhz(intOrNull(hardware.get("cpuFrequencyMhz")));
        snapshot.setRamTotalBytes(longOrNull(hardware.get("ramTotalBytes")));
        snapshot.setRamAvailableBytes(longOrNull(hardware.get("ramAvailableBytes")));

        snapshot.setOsName(strOr(hardware.get("osName"), null));
        snapshot.setOsVersion(strOr(hardware.get("osVersion"), null));
        snapshot.setOsKernel(strOr(hardware.get("osKernel"), null));
        snapshot.setOsArch(strOr(hardware.get("osArch"), null));

        snapshot.setBiosVendor(strOr(hardware.get("biosVendor"), null));
        snapshot.setBiosVersion(strOr(hardware.get("biosVersion"), null));
        snapshot.setManufacturer(strOr(hardware.get("manufacturer"), null));
        snapshot.setSystemModel(strOr(hardware.get("systemModel"), null));

        snapshot.setDomainJoined(boolOrNull(hardware.get("domainJoined")));
        snapshot.setDomainName(strOr(hardware.get("domainName"), null));

        Object lastBoot = hardware.get("lastBootAt");
        if (lastBoot != null) {
            snapshot.setLastBootAt(parseInstant(lastBoot));
        }

        snapshot.setCollectedAt(parseInstant(hardware.get("collectedAt")));
        snapshot.setPayloadHashSha256(payloadHash);

        // Probe errors (bounded {code, summary} objects). Build the
        // bounded list FIRST so the redacted_payload projection
        // below can substitute it for the raw probeErrors subtree
        // (Codex 019e7007 post-impl iter-1 must-fix #2: stack-trace
        // / raw probe text must not leak via redacted_payload).
        List<Map<String, Object>> boundedProbeErrors = new ArrayList<>();
        Object probeErrors = hardware.get("probeErrors");
        if (probeErrors instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> em) {
                    Map<String, Object> entry = new HashMap<>();
                    Object code = em.get("code");
                    Object summary = em.get("summary");
                    if (code != null) entry.put("code", String.valueOf(code));
                    if (summary != null) {
                        String s = String.valueOf(summary);
                        if (s.length() > 256) {
                            s = s.substring(0, 256);
                        }
                        entry.put("summary", s);
                    }
                    if (!entry.isEmpty()) {
                        boundedProbeErrors.add(entry);
                    }
                }
            }
        }
        snapshot.setProbeErrors(boundedProbeErrors);

        // Redacted payload — caller already sanitized scalar values
        // and stripped known sensitive keys. Substitute the bounded
        // probeErrors so any stack-trace / raw command output the
        // agent surfaced does not land here (Codex iter-1 #2).
        Map<String, Object> redactedPayload = new HashMap<>(hardware);
        if (redactedPayload.containsKey("probeErrors")) {
            redactedPayload.put("probeErrors", boundedProbeErrors);
        }
        snapshot.setRedactedPayload(redactedPayload);

        // Disks.
        Object disksRaw = hardware.get("disks");
        if (disksRaw instanceof List<?> disks) {
            for (Object diskObj : disks) {
                if (diskObj instanceof Map<?, ?> diskMap) {
                    EndpointHardwareInventoryDisk disk = buildDisk(snapshot, diskMap);
                    snapshot.getDisks().add(disk);
                }
            }
        }

        // Network interfaces.
        Object interfacesRaw = hardware.get("networkInterfaces");
        if (interfacesRaw instanceof List<?> interfaces) {
            for (Object intfObj : interfaces) {
                if (intfObj instanceof Map<?, ?> intfMap) {
                    EndpointHardwareInventoryNetworkInterface intf =
                            buildNetworkInterface(snapshot, intfMap);
                    snapshot.getNetworkInterfaces().add(intf);
                }
            }
        }

        return snapshot;
    }

    private EndpointHardwareInventoryDisk buildDisk(
            EndpointHardwareInventorySnapshot snapshot, Map<?, ?> diskMap) {
        EndpointHardwareInventoryDisk disk = new EndpointHardwareInventoryDisk();
        disk.setSnapshot(snapshot);
        disk.setDevicePath(strOr(diskMap.get("devicePath"), null));
        disk.setModel(strOr(diskMap.get("model"), null));
        disk.setCapacityBytes(longOrNull(diskMap.get("capacityBytes")));
        disk.setFreeBytes(longOrNull(diskMap.get("freeBytes")));
        disk.setRemovable(boolOrNull(diskMap.get("isRemovable")));

        Object mediaType = diskMap.get("mediaType");
        if (mediaType instanceof String s && !s.isBlank()) {
            try {
                disk.setMediaType(EndpointHardwareInventoryDisk.MediaType
                        .valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                disk.setMediaType(EndpointHardwareInventoryDisk.MediaType.UNKNOWN);
            }
        }

        Object busType = diskMap.get("busType");
        if (busType instanceof String s && !s.isBlank()) {
            try {
                disk.setBusType(EndpointHardwareInventoryDisk.BusType
                        .valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                disk.setBusType(EndpointHardwareInventoryDisk.BusType.UNKNOWN);
            }
        }

        return disk;
    }

    @SuppressWarnings("unchecked")
    private EndpointHardwareInventoryNetworkInterface buildNetworkInterface(
            EndpointHardwareInventorySnapshot snapshot, Map<?, ?> intfMap) {
        EndpointHardwareInventoryNetworkInterface intf = new EndpointHardwareInventoryNetworkInterface();
        intf.setSnapshot(snapshot);
        intf.setName(strOr(intfMap.get("name"), null));
        // Sanitizer already normalized MAC to lowercase canonical.
        intf.setMacAddress(strOr(intfMap.get("macAddress"), null));

        Object interfaceType = intfMap.get("interfaceType");
        if (interfaceType instanceof String s && !s.isBlank()) {
            try {
                intf.setInterfaceType(EndpointHardwareInventoryNetworkInterface.InterfaceType
                        .valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                intf.setInterfaceType(EndpointHardwareInventoryNetworkInterface.InterfaceType.UNKNOWN);
            }
        }

        Object linkState = intfMap.get("linkState");
        if (linkState instanceof String s && !s.isBlank()) {
            try {
                intf.setLinkState(EndpointHardwareInventoryNetworkInterface.LinkState
                        .valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                intf.setLinkState(EndpointHardwareInventoryNetworkInterface.LinkState.UNKNOWN);
            }
        }

        Object ips = intfMap.get("ipAddresses");
        if (ips instanceof List<?> ipList) {
            List<String> bounded = new ArrayList<>();
            for (Object ip : ipList) {
                if (ip instanceof String s) {
                    bounded.add(s);
                }
            }
            intf.setIpAddresses(bounded);
        }
        return intf;
    }

    private HardwareInventorySnapshotPersistedEvent buildAuditEvent(
            EndpointHardwareInventorySnapshot snapshot, EndpointCommand command) {
        return new HardwareInventorySnapshotPersistedEvent(
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getId(),
                command != null ? command.getId() : null,
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getPayloadHashSha256(),
                snapshot.getDisks().size(),
                snapshot.getNetworkInterfaces().size(),
                snapshot.getCollectedAt());
    }

    /** Sanity bounds for collectedAt (Codex iter-1 must-fix #4):
     * agent-reported timestamps must be within the last 90 days
     * and not more than 1 hour in the future. */
    private static final Duration COLLECTED_AT_PAST_BOUND = Duration.ofDays(90);
    private static final Duration COLLECTED_AT_FUTURE_BOUND = Duration.ofHours(1);

    private static Instant parseInstant(Object value) {
        Instant parsed = parseInstantRaw(value);
        // Range sanity — fail closed if the timestamp is unreasonably
        // old or in the future.
        Instant now = Instant.now();
        if (parsed.isAfter(now.plus(COLLECTED_AT_FUTURE_BOUND))) {
            throw new IllegalArgumentException(
                    "collectedAt too far in the future: " + parsed);
        }
        if (parsed.isBefore(now.minus(COLLECTED_AT_PAST_BOUND))) {
            throw new IllegalArgumentException(
                    "collectedAt too far in the past: " + parsed);
        }
        return parsed;
    }

    private static Instant parseInstantRaw(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("collectedAt required");
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof String s) {
            try {
                return Instant.parse(s.trim());
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid ISO-8601 timestamp: " + s);
            }
        }
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        throw new IllegalArgumentException(
                "Unsupported timestamp type: " + value.getClass().getSimpleName());
    }

    private static String sha256Hex(Map<String, Object> hardware) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Canonical serialization is overkill here — the agent
            // payload is already a deterministic JSON; we hash its
            // toString() representation which is sufficient for
            // change-detection (not for adversarial integrity).
            byte[] digest = md.digest(hardware.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Integer intOr(Object value, int fallback) {
        Integer parsed = intOrNull(value);
        return parsed != null ? parsed : fallback;
    }

    private static Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private static Short shortOr(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null) return null;
        if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) return null;
        return parsed.shortValue();
    }

    private static Long longOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private static String strOr(Object value, String fallback) {
        if (value == null) return fallback;
        return String.valueOf(value);
    }

    private static Boolean boolOr(Object value, Boolean fallback) {
        Boolean parsed = boolOrNull(value);
        return parsed != null ? parsed : fallback;
    }

    private static Boolean boolOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }
}
