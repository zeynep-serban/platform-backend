package com.example.endpointadmin.service;

import com.example.endpointadmin.event.DeviceHealthSnapshotPersistedEvent;
import com.example.endpointadmin.security.DeviceHealthPayloadPolicy;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceHealthDisk;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — device-health ingest + query service (Faz 22.5, AG-033 ingest).
 * Mirrors the BE-022 {@link EndpointHardwareInventoryService} precedent
 * EXACTLY, against the device-health wire contract
 * (schema/endpoint-device-health-payload-v1.schema.json, gitops PR #1143
 * commit {@code ddd5e326}).
 *
 * <p>Caller contract: the agent SUBMIT-result hook in
 * {@link EndpointAgentCommandService} invokes
 * {@link #ingest(EndpointDevice, EndpointCommand, EndpointCommandResult, Map)}
 * after the command-result row has been persisted (with the sanitized
 * {@code effectiveDetails}). The hook only calls this method when the
 * sanitized {@code effectiveDetails} actually carries a
 * {@code details.inventory.deviceHealth} block — see
 * {@link #hasDeviceHealthBlock(Map)} for the canonical predicate.
 *
 * <p>Dual idempotency (parity with BE-022 + the just-merged BE-022Q
 * lesson):
 * <ol>
 *   <li>Primary: partial UNIQUE on {@code source_command_result_id} —
 *       the hook can re-deliver the same command-result without
 *       duplicating; this service catches the DB-layer race
 *       ({@link DataIntegrityViolationException}) and returns the
 *       existing row.</li>
 *   <li>Secondary: payload-hash deep-equality dedupe — when the agent
 *       re-collects byte-identical device-health under a DIFFERENT
 *       command-result (so the first probe misses), no-op instead of
 *       appending a duplicate row. The hash is computed once and reused
 *       for the persisted entity so probe and stored value cannot
 *       diverge. The dedupe query uses a direct VARCHAR {@code =} via
 *       {@code cast(:hash as string)} — NEVER {@code lower(bytea)}.</li>
 * </ol>
 *
 * <p>Append-only history: this service NEVER mutates a previous
 * snapshot. Every successful ingest produces a new row.
 *
 * <p>{@code collectedAt} provenance: the device-health wire block does
 * NOT carry a timestamp (unlike the hardware block). The snapshot's
 * {@code collected_at} is therefore derived from the command-result's
 * server-controlled {@code reportedAt} (falling back to now() for a
 * manual/test ingest with no result), so it cannot be spoofed by the
 * agent payload.
 */
@Service
public class EndpointDeviceHealthService {

    private static final Logger log = LoggerFactory.getLogger(EndpointDeviceHealthService.class);

    /**
     * Bounded probeError summary cap. Single source of truth shared with
     * {@link DeviceHealthPayloadPolicy#SUMMARY_MAX_LEN} (= contract
     * {@code maxLength: 200}). The policy already bounds the summary in the
     * canonical projection (so the command-result payload is bounded too);
     * this constant is the same value, applied defensively when the entity
     * scalar is built (Codex 019e… P1-3 must-fix: 256 → 200).
     */
    private static final int SUMMARY_MAX_LEN = DeviceHealthPayloadPolicy.SUMMARY_MAX_LEN;

    private final EndpointDeviceHealthSnapshotRepository repository;
    private final ApplicationEventPublisher events;

    public EndpointDeviceHealthService(
            EndpointDeviceHealthSnapshotRepository repository,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Check whether the sanitized {@code effectiveDetails} carries a
     * device-health sub-tree the hook should ingest.
     */
    public static boolean hasDeviceHealthBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("deviceHealth") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("deviceHealth") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointDeviceHealthSnapshot ingest(
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
            Optional<EndpointDeviceHealthSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Device-health ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> deviceHealth = extractDeviceHealth(effectiveDetails);
        if (deviceHealth == null) {
            throw new IllegalStateException(
                    "ingest called without a deviceHealth block — hook should check hasDeviceHealthBlock() first");
        }

        // Secondary payload-hash deep-equality dedupe (BE-022Q lesson):
        // byte-identical re-collection under a different command-result
        // must no-op rather than append. The hash is computed once and
        // reused for the persisted entity.
        String payloadHash = sha256Hex(deviceHealth);
        Optional<EndpointDeviceHealthSnapshot> identical =
                repository
                        .findByTenantDeviceAndPayloadHash(
                                device.getTenantId(), device.getId(), payloadHash,
                                PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Device-health ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();
        EndpointDeviceHealthSnapshot snapshot =
                buildSnapshot(device, commandResultId, deviceHealth, payloadHash, collectedAt);

        try {
            // saveAndFlush so any partial-UNIQUE violation on
            // source_command_result_id surfaces NOW (and is caught by the
            // block below), rather than at transaction commit when this
            // catch is no longer in scope (BE-022 iter-1 must-fix #1).
            EndpointDeviceHealthSnapshot persisted = repository.saveAndFlush(snapshot);
            log.info("Device-health snapshot persisted device_id={} snapshot_id={} schema={} supported={} probeComplete={}",
                    device.getId(), persisted.getId(), persisted.getSchemaVersion(),
                    persisted.getSupported(), persisted.getProbeComplete());
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

    public Optional<EndpointDeviceHealthSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    public Page<EndpointDeviceHealthSnapshot> findHistory(
            UUID tenantId, UUID deviceId, Pageable pageable) {
        return repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId, pageable);
    }

    // ------------------------------------------------------------------
    // Internals — buildSnapshot composes the entity from the sanitized
    // device-health sub-tree. Missing optional scalars persist as NULL.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractDeviceHealth(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("deviceHealth") instanceof Map<?, ?> dh) {
            return (Map<String, Object>) dh;
        }
        Object top = effectiveDetails.get("deviceHealth");
        if (top instanceof Map<?, ?> dh) {
            return (Map<String, Object>) dh;
        }
        return null;
    }

    private EndpointDeviceHealthSnapshot buildSnapshot(
            EndpointDevice device, UUID commandResultId, Map<String, Object> dh,
            String payloadHash, Instant collectedAt) {
        EndpointDeviceHealthSnapshot snapshot = new EndpointDeviceHealthSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        // Required v1 fields — the policy projection (DeviceHealthPayloadPolicy)
        // already fail-closed REJECTED a payload missing/wrong-typed on any
        // of these (Codex 019e… P1-2). The service must NOT synthesize a
        // "healthy" default (that would turn a {"schemaVersion":1} reject
        // into a healthy-looking snapshot). We therefore read the canonical
        // projection directly — never an Or(default) fallback.
        snapshot.setSchemaVersion(requireShort(dh.get("schemaVersion")));
        snapshot.setSupported(requireBool(dh.get("supported")));
        snapshot.setProbeComplete(requireBool(dh.get("probeComplete")));
        snapshot.setAnyLowDisk(requireBool(dh.get("anyLowDisk")));
        snapshot.setFixedDiskCount(requireInt(dh.get("fixedDiskCount")));
        snapshot.setFixedDisksTruncated(requireBool(dh.get("fixedDisksTruncated")));
        snapshot.setMaxFixedDisks(requireInt(dh.get("maxFixedDisks")));
        snapshot.setSourceUsed(requireSourceUsed(dh.get("sourceUsed")));
        snapshot.setProbeDurationMs(requireInt(dh.get("probeDurationMs")));

        // Memory summary scalars (full byte/commit fields stay in
        // redacted_payload). memory is a required object (policy-enforced),
        // and its required subfields are present + range-checked.
        Object memory = dh.get("memory");
        if (memory instanceof Map<?, ?> mem) {
            snapshot.setMemoryUsedPercent(requireShortPercent(mem.get("usedPercent")));
            snapshot.setMemoryHighPressure(requireBool(mem.get("highPressureWarning")));
        }

        // Uptime summary scalars (required object, required subfields).
        Object uptime = dh.get("uptime");
        if (uptime instanceof Map<?, ?> up) {
            snapshot.setUptimeDays(requireInt(up.get("uptimeDays")));
            snapshot.setUptimeSeconds(requireLong(up.get("uptimeSeconds")));
            snapshot.setLastBootEpochSec(requireLong(up.get("lastBootEpochSec")));
            snapshot.setLongUptimeWarning(requireBool(up.get("longUptimeWarning")));
        }

        snapshot.setCollectedAt(collectedAt);
        snapshot.setPayloadHashSha256(payloadHash);

        // Probe errors (bounded {code, summary} objects). Build the
        // bounded list FIRST so the redacted_payload projection below can
        // substitute it for the raw probeErrors subtree (parity with
        // BE-022 must-fix #2: raw probe text must not leak via
        // redacted_payload).
        List<Map<String, Object>> boundedProbeErrors = boundProbeErrors(dh.get("probeErrors"));
        snapshot.setProbeErrors(boundedProbeErrors);

        // Redacted payload — caller already validated + sanitized the
        // block. Substitute the bounded probeErrors so any extra raw
        // fields the agent surfaced do not land here.
        Map<String, Object> redactedPayload = new HashMap<>(dh);
        if (redactedPayload.containsKey("probeErrors")) {
            redactedPayload.put("probeErrors", boundedProbeErrors);
        }
        snapshot.setRedactedPayload(redactedPayload);

        // Fixed-disk facets (driveLetter-only — the payload policy has
        // already fail-closed rejected any out-of-shape disk key).
        Object disksRaw = dh.get("fixedDisks");
        if (disksRaw instanceof List<?> disks) {
            for (Object diskObj : disks) {
                if (diskObj instanceof Map<?, ?> diskMap) {
                    snapshot.getDisks().add(buildDisk(snapshot, diskMap));
                }
            }
        }

        return snapshot;
    }

    private static List<Map<String, Object>> boundProbeErrors(Object probeErrorsRaw) {
        List<Map<String, Object>> bounded = new ArrayList<>();
        if (probeErrorsRaw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> em) {
                    Map<String, Object> entry = new HashMap<>();
                    Object code = em.get("code");
                    Object source = em.get("source");
                    Object summary = em.get("summary");
                    if (code != null) entry.put("code", String.valueOf(code));
                    if (source != null) entry.put("source", String.valueOf(source));
                    if (summary != null) {
                        String s = String.valueOf(summary);
                        if (s.length() > SUMMARY_MAX_LEN) {
                            s = s.substring(0, SUMMARY_MAX_LEN);
                        }
                        entry.put("summary", s);
                    }
                    if (!entry.isEmpty()) {
                        bounded.add(entry);
                    }
                }
            }
        }
        return bounded;
    }

    private EndpointDeviceHealthDisk buildDisk(
            EndpointDeviceHealthSnapshot snapshot, Map<?, ?> diskMap) {
        EndpointDeviceHealthDisk disk = new EndpointDeviceHealthDisk();
        disk.setSnapshot(snapshot);
        disk.setDriveLetter(strOr(diskMap.get("driveLetter"), null));
        disk.setTotalBytes(longOrNull(diskMap.get("totalBytes")));
        disk.setFreeBytes(longOrNull(diskMap.get("freeBytes")));
        disk.setFreePercent(shortPercentOrNull(diskMap.get("freePercent")));
        disk.setLowDiskWarning(boolOrNull(diskMap.get("lowDiskWarning")));
        return disk;
    }

    private DeviceHealthSnapshotPersistedEvent buildAuditEvent(
            EndpointDeviceHealthSnapshot snapshot, EndpointCommand command) {
        return new DeviceHealthSnapshotPersistedEvent(
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getId(),
                command != null ? command.getId() : null,
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getAnyLowDisk(),
                snapshot.getMemoryHighPressure(),
                snapshot.getLongUptimeWarning(),
                snapshot.getPayloadHashSha256(),
                snapshot.getDisks().size(),
                snapshot.getCollectedAt());
    }

    private static String sha256Hex(Map<String, Object> deviceHealth) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Change-detection hash over the deterministic agent JSON
            // (not adversarial integrity), mirroring the BE-022 approach.
            byte[] digest = md.digest(deviceHealth.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ------------------------------------------------------------------
    // Strict required-field accessors (Codex 019e… P1-2). These are used
    // only for v1 REQUIRED fields, which the policy already fail-closed
    // rejected when missing/wrong-typed. They therefore NEVER fall back to
    // a "healthy" default; a null/wrong-typed value here is a code
    // invariant violation (policy bypassed) → fail loud, not silent.
    // ------------------------------------------------------------------

    private static String requireSourceUsed(Object value) {
        String s = String.valueOf(value);
        if (!("win32".equals(s) || "none".equals(s))) {
            throw new IllegalStateException(
                    "sourceUsed must be win32|none after policy projection but was '" + s + "'");
        }
        return s;
    }

    private static int requireInt(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null) {
            throw new IllegalStateException(
                    "Required integer field null after policy projection: " + value);
        }
        return parsed;
    }

    private static long requireLong(Object value) {
        Long parsed = longOrNull(value);
        if (parsed == null) {
            throw new IllegalStateException(
                    "Required long field null after policy projection: " + value);
        }
        return parsed;
    }

    private static short requireShort(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null || parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
            throw new IllegalStateException(
                    "Required short field null/out-of-range after policy projection: " + value);
        }
        return parsed.shortValue();
    }

    private static short requireShortPercent(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null) {
            throw new IllegalStateException(
                    "Required percent field null after policy projection: " + value);
        }
        if (parsed < 0) return (short) 0;
        if (parsed > 100) return (short) 100;
        return parsed.shortValue();
    }

    private static boolean requireBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException(
                "Required boolean field not Boolean after policy projection: " + value);
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

    /** Parse a 0..100 percentage into a Short, clamping/dropping
     *  out-of-range values (the policy already range-checks, but the
     *  service must persist a SMALLINT-safe value). Used for the OPTIONAL
     *  disk-facet freePercent. */
    private static Short shortPercentOrNull(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null) return null;
        if (parsed < 0) return (short) 0;
        if (parsed > 100) return (short) 100;
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

    private static Boolean boolOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }
}
