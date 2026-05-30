package com.example.endpointadmin.service;

import com.example.endpointadmin.event.OutdatedSoftwareSnapshotPersistedEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * BE — outdated-software ingest + query service (Faz 22.5, AG-036 ingest).
 * Mirrors the AG-033 {@link EndpointDeviceHealthService} precedent against
 * the outdated-software wire contract
 * (schema/endpoint-outdated-software-payload-v1.schema.json, gitops PR #1145
 * commit {@code 73f0db0f}), but reuses the BE-024 atomicity pattern (native
 * {@code INSERT ... ON CONFLICT ... DO NOTHING}) for the snapshot write
 * instead of {@code saveAndFlush + catch}.
 *
 * <p>Caller contract: the agent SUBMIT-result hook in
 * {@link EndpointAgentCommandService} invokes
 * {@link #ingest(EndpointDevice, EndpointCommand, EndpointCommandResult, Map)}
 * after the command-result row has been persisted (with the sanitized
 * {@code effectiveDetails}). The hook only calls this method when the
 * sanitized {@code effectiveDetails} actually carries a
 * {@code details.inventory.outdatedSoftware} block — see
 * {@link #hasOutdatedSoftwareBlock(Map)} for the canonical predicate.
 *
 * <p>Dual idempotency:
 * <ol>
 *   <li>Primary: the partial UNIQUE on {@code source_command_result_id}
 *       (service pre-probe fast-path + native {@code ON CONFLICT DO NOTHING}
 *       race-safe guard). The hook can re-deliver the same command-result
 *       without duplicating; a duplicate is a clean no-op.</li>
 *   <li>Secondary: payload-hash deep-equality dedupe — when the agent
 *       re-collects byte-identical outdated-software under a DIFFERENT
 *       command-result (so the first probe misses), no-op instead of
 *       appending a duplicate row. The hash is computed once and reused for
 *       the persisted entity so probe and stored value cannot diverge. The
 *       dedupe query uses a direct VARCHAR {@code =} via
 *       {@code cast(:hash as string)} — NEVER {@code lower(bytea)}.</li>
 * </ol>
 *
 * <p>Atomicity (BE-024): a duplicate {@code source_command_result_id} is a
 * clean no-op; every other CHECK / FK violation propagates and rolls back
 * the whole ingest transaction (no broad-catch swallow, no PG rollback-only
 * leak into the audit/commit stage).
 *
 * <p>Append-only history: this service NEVER mutates a previous snapshot.
 * Every successful ingest produces a new row.
 *
 * <p>{@code collectedAt} provenance: the outdated-software wire block does
 * NOT carry a timestamp. The snapshot's {@code collected_at} is therefore
 * derived from the command-result's server-controlled {@code reportedAt}
 * (falling back to now() for a manual/test ingest with no result), so it
 * cannot be spoofed by the agent payload.
 *
 * <p>Read-only boundary: the probe is read-only ('winget upgrade
 * --include-returning-apps --source winget'); this service is a persist/query
 * path and MUST NOT trigger any agent-side mutation from the payload.
 */
@Service
public class EndpointOutdatedSoftwareService {

    private static final Logger log = LoggerFactory.getLogger(EndpointOutdatedSoftwareService.class);

    private final EndpointOutdatedSoftwareSnapshotRepository repository;
    private final ApplicationEventPublisher events;

    public EndpointOutdatedSoftwareService(
            EndpointOutdatedSoftwareSnapshotRepository repository,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Check whether the sanitized {@code effectiveDetails} carries an
     * outdated-software sub-tree the hook should ingest.
     */
    public static boolean hasOutdatedSoftwareBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("outdatedSoftware") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("outdatedSoftware") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointOutdatedSoftwareSnapshot ingest(
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
            Optional<EndpointOutdatedSoftwareSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Outdated-software ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> outdated = extractOutdatedSoftware(effectiveDetails);
        if (outdated == null) {
            throw new IllegalStateException(
                    "ingest called without an outdatedSoftware block — hook should check hasOutdatedSoftwareBlock() first");
        }

        // Secondary payload-hash deep-equality dedupe (BE-022Q lesson):
        // byte-identical re-collection under a different command-result
        // must no-op rather than append. The hash is computed once and
        // reused for the persisted entity.
        String payloadHash = sha256Hex(outdated);
        Optional<EndpointOutdatedSoftwareSnapshot> identical =
                repository
                        .findByTenantDeviceAndPayloadHash(
                                device.getTenantId(), device.getId(), payloadHash,
                                PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Outdated-software ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();
        EndpointOutdatedSoftwareSnapshot snapshot =
                buildSnapshot(device, commandResultId, outdated, payloadHash, collectedAt);

        // BE-024 atomicity: native ON CONFLICT (source_command_result_id)
        // DO NOTHING — a duplicate is a clean no-op (returns null), every
        // other V20 violation propagates + rolls back the whole tx.
        UUID insertedId = repository.insertIfNewSourceCommandResult(snapshot);
        if (insertedId == null) {
            // Lost a race on source_command_result_id: another concurrent
            // SUBMIT-result delivery inserted first. Return its row.
            if (commandResultId != null) {
                Optional<EndpointOutdatedSoftwareSnapshot> winner =
                        repository.findBySourceCommandResultId(commandResultId);
                if (winner.isPresent()) {
                    log.debug("Outdated-software ingest no-op for command_result_id={} (lost ON CONFLICT race)",
                            commandResultId);
                    return winner.get();
                }
            }
            // No command-result id and yet a no-op should be impossible (NULL
            // is outside the partial index, so it always inserts). Fail loud.
            throw new IllegalStateException(
                    "Outdated-software insert returned no-op without a source_command_result_id");
        }

        log.info("Outdated-software snapshot persisted device_id={} snapshot_id={} schema={} supported={} probeComplete={} upgradeCount={}",
                device.getId(), insertedId, snapshot.getSchemaVersion(),
                snapshot.getSupported(), snapshot.getProbeComplete(),
                snapshot.getUpgradeCount());
        events.publishEvent(buildAuditEvent(snapshot, command));
        return snapshot;
    }

    public Optional<EndpointOutdatedSoftwareSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    public Page<EndpointOutdatedSoftwareSnapshot> findHistory(
            UUID tenantId, UUID deviceId, Pageable pageable) {
        return repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId, pageable);
    }

    // ------------------------------------------------------------------
    // Internals — buildSnapshot composes the entity from the sanitized
    // outdated-software sub-tree.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractOutdatedSoftware(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("outdatedSoftware") instanceof Map<?, ?> os) {
            return (Map<String, Object>) os;
        }
        Object top = effectiveDetails.get("outdatedSoftware");
        if (top instanceof Map<?, ?> os) {
            return (Map<String, Object>) os;
        }
        return null;
    }

    private EndpointOutdatedSoftwareSnapshot buildSnapshot(
            EndpointDevice device, UUID commandResultId, Map<String, Object> os,
            String payloadHash, Instant collectedAt) {
        EndpointOutdatedSoftwareSnapshot snapshot = new EndpointOutdatedSoftwareSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        // Required v1 fields — the policy projection (OutdatedSoftwarePayloadPolicy)
        // already fail-closed REJECTED a payload missing/wrong-typed on any
        // of these. The service must NOT synthesize an "up to date" default
        // (that would turn a {"schemaVersion":1} reject into an
        // up-to-date-looking snapshot). We therefore read the canonical
        // projection directly — never an Or(default) fallback.
        snapshot.setSchemaVersion(requireShort(os.get("schemaVersion")));
        snapshot.setSupported(requireBool(os.get("supported")));
        snapshot.setProbeComplete(requireBool(os.get("probeComplete")));
        snapshot.setUpgradeCount(requireInt(os.get("upgradeCount")));
        snapshot.setUpgradeTruncated(requireBool(os.get("upgradeTruncated")));
        snapshot.setMaxUpgrade(requireInt(os.get("maxUpgrade")));
        snapshot.setSourceUsed(requireSourceUsed(os.get("sourceUsed")));
        snapshot.setProbeDurationMs(requireInt(os.get("probeDurationMs")));

        snapshot.setCollectedAt(collectedAt);
        snapshot.setPayloadHashSha256(payloadHash);

        // Probe errors (bounded {source, code, summary} objects). Build the
        // bounded list FIRST so the redacted_payload projection below can
        // substitute it for the raw probeErrors subtree (parity with the
        // device-health precedent: raw probe text must not leak via
        // redacted_payload).
        List<Map<String, Object>> boundedProbeErrors = boundProbeErrors(os.get("probeErrors"));
        snapshot.setProbeErrors(boundedProbeErrors);

        // Redacted payload — caller already validated + sanitized the block.
        // Substitute the bounded probeErrors so any extra raw fields the agent
        // surfaced do not land here.
        Map<String, Object> redactedPayload = new HashMap<>(os);
        if (redactedPayload.containsKey("probeErrors")) {
            redactedPayload.put("probeErrors", boundedProbeErrors);
        }
        snapshot.setRedactedPayload(redactedPayload);

        // Per-upgradeable-package facets (the payload policy has already
        // fail-closed rejected any out-of-shape package key, so each entry
        // carries exactly {packageId, installedVersion, availableVersion}).
        Object upgradeRaw = os.get("upgrade");
        if (upgradeRaw instanceof List<?> upgrades) {
            for (Object pkgObj : upgrades) {
                if (pkgObj instanceof Map<?, ?> pkgMap) {
                    snapshot.getPackages().add(buildPackage(snapshot, pkgMap));
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
                        if (s.length() > OutdatedSoftwarePayloadPolicy.SUMMARY_MAX_LEN) {
                            s = s.substring(0, OutdatedSoftwarePayloadPolicy.SUMMARY_MAX_LEN);
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

    private EndpointOutdatedSoftwarePackage buildPackage(
            EndpointOutdatedSoftwareSnapshot snapshot, Map<?, ?> pkgMap) {
        EndpointOutdatedSoftwarePackage pkg = new EndpointOutdatedSoftwarePackage();
        pkg.setSnapshot(snapshot);
        // The three contract fields are required + already validated by the
        // policy; the service reads them directly. requireString fails loud if
        // the policy was somehow bypassed (no silent null persist into a
        // NOT NULL column).
        pkg.setPackageId(requireString(pkgMap.get("packageId"), "packageId"));
        pkg.setInstalledVersion(requireString(pkgMap.get("installedVersion"), "installedVersion"));
        pkg.setAvailableVersion(requireString(pkgMap.get("availableVersion"), "availableVersion"));
        return pkg;
    }

    private OutdatedSoftwareSnapshotPersistedEvent buildAuditEvent(
            EndpointOutdatedSoftwareSnapshot snapshot, EndpointCommand command) {
        boolean possiblyTruncated = snapshot.getUpgradeCount() != null
                && snapshot.getMaxUpgrade() != null
                && snapshot.getUpgradeCount().equals(snapshot.getMaxUpgrade());
        return new OutdatedSoftwareSnapshotPersistedEvent(
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getId(),
                command != null ? command.getId() : null,
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getUpgradeCount(),
                snapshot.getUpgradeTruncated(),
                possiblyTruncated,
                snapshot.getSourceUsed(),
                snapshot.getPayloadHashSha256(),
                snapshot.getPackages().size(),
                snapshot.getCollectedAt());
    }

    private static String sha256Hex(Map<String, Object> outdated) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Change-detection hash over the deterministic agent JSON
            // (not adversarial integrity), mirroring the BE-022 approach.
            byte[] digest = md.digest(outdated.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ------------------------------------------------------------------
    // Strict required-field accessors. These are used only for v1 REQUIRED
    // fields, which the policy already fail-closed rejected when
    // missing/wrong-typed. They therefore NEVER fall back to an "up to date"
    // default; a null/wrong-typed value here is a code invariant violation
    // (policy bypassed) → fail loud, not silent.
    // ------------------------------------------------------------------

    private static String requireSourceUsed(Object value) {
        String s = String.valueOf(value);
        if (!("winget".equals(s) || "none".equals(s))) {
            throw new IllegalStateException(
                    "sourceUsed must be winget|none after policy projection but was '" + s + "'");
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

    private static short requireShort(Object value) {
        Integer parsed = intOrNull(value);
        if (parsed == null || parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
            throw new IllegalStateException(
                    "Required short field null/out-of-range after policy projection: " + value);
        }
        return parsed.shortValue();
    }

    private static boolean requireBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException(
                "Required boolean field not Boolean after policy projection: " + value);
    }

    private static String requireString(Object value, String field) {
        if (value instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new IllegalStateException(
                "Required package field '" + field + "' null/blank after policy projection: " + value);
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
}
