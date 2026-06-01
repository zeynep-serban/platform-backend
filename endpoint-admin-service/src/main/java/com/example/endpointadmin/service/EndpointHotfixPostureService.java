package com.example.endpointadmin.service;

import com.example.endpointadmin.event.HotfixPostureSnapshotPersistedEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHotfixPostureInstalled;
import com.example.endpointadmin.model.EndpointHotfixPosturePending;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingCategoryCount;
import com.example.endpointadmin.model.EndpointHotfixPosturePendingKb;
import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;
import com.example.endpointadmin.repository.EndpointHotfixPostureSnapshotRepository;
import com.example.endpointadmin.security.HotfixPosturePayloadPolicy;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — hotfix-posture ingest + query service (Faz 22.5, AG-037 ingest).
 * Mirrors the AG-036 {@link EndpointOutdatedSoftwareService} pattern
 * against the AG-037 wire contract (platform-agent
 * docs/COMMAND-CONTRACT.md §16, PR #45 merged 2026-06-01) with the
 * Codex 019e81fe iter-3 + iter-4 absorbed:
 *
 * <ul>
 *   <li>Targetless {@code ON CONFLICT DO NOTHING} write (iter-3 P1.1) so
 *       BOTH the partial UNIQUE on {@code source_command_result_id} and
 *       the full UNIQUE on {@code (tenant, device, payload_hash)} are
 *       caught race-cleanly.</li>
 *   <li>Sequential winner lookup (iter-4): when the targetless conflict
 *       fires, the service tries the source probe FIRST (when non-null),
 *       FALLS THROUGH to the hash probe (NOT mutually exclusive), and
 *       fails loud only when neither lookup resolves the winner — UUID
 *       collision / future unique constraints are never silently
 *       swallowed.</li>
 *   <li>Canonical-form payload hash (iter-3 ANSWER + iter-4): SHA-256 of
 *       a deterministic {@link LinkedHashMap} carrying the policy-
 *       projected normalized tree. EXCLUDES wire {@code collectedAt} +
 *       {@code probeDurationMs} (timing-only); INCLUDES
 *       {@code lastDetectAt} + {@code lastInstallAt} (posture evidence —
 *       a stale detection state IS a posture change). Timestamp fields
 *       normalize to ISO-8601 UTC before hashing so logically identical
 *       values do not diverge between unit tests passing {@link Instant}
 *       and real requests carrying JSON strings.</li>
 *   <li>AFTER_COMMIT bounded event: {@link HotfixPostureSnapshotPersistedEvent}
 *       carries scalar metadata ONLY — no raw KB, no description, no
 *       severity rollup (Codex iter-2 P1.6 ANSWER — wire can't carry
 *       full severity distribution when capped).</li>
 * </ul>
 *
 * <h3>Caller contract</h3>
 *
 * <p>The agent SUBMIT-result hook in {@code EndpointAgentCommandService}
 * invokes {@link #ingest(EndpointDevice, EndpointCommand, EndpointCommandResult, Map)}
 * after the command-result row has been persisted (with the sanitized
 * {@code effectiveDetails}). The hook only calls this method when the
 * sanitized payload actually carries a
 * {@code details.inventory.hotfixPosture} block — see
 * {@link #hasHotfixPostureBlock(Map)}.
 *
 * <h3>{@code collectedAt} provenance</h3>
 *
 * <p>The hotfix-posture wire block carries a {@code collectedAt}
 * timestamp but the SNAPSHOT's {@code collected_at} is derived from the
 * command-result's server-controlled {@code reportedAt} (falling back to
 * {@code now()} for a manual/test ingest with no result), so it cannot
 * be spoofed by the agent payload. The wire {@code collectedAt} is
 * preserved inside {@code redactedPayload} only.
 */
@Service
public class EndpointHotfixPostureService {

    private static final Logger log = LoggerFactory.getLogger(EndpointHotfixPostureService.class);

    private final EndpointHotfixPostureSnapshotRepository repository;
    private final ApplicationEventPublisher events;

    public EndpointHotfixPostureService(
            EndpointHotfixPostureSnapshotRepository repository,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** Predicate the agent SUBMIT-result hook uses to gate ingest. */
    public static boolean hasHotfixPostureBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("hotfixPosture") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("hotfixPosture") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointHotfixPostureSnapshot ingest(
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
            Optional<EndpointHotfixPostureSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                log.debug("Hotfix posture ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return existing.get();
            }
        }

        Map<String, Object> hp = extractHotfixPosture(effectiveDetails);
        if (hp == null) {
            throw new IllegalStateException(
                    "ingest called without a hotfixPosture block — hook should check"
                            + " hasHotfixPostureBlock() first");
        }

        // Canonical-form payload hash (Codex iter-3 ANSWER + iter-4):
        // computed from a deterministic LinkedHashMap built from the
        // policy-projected tree, EXCLUDING wire collectedAt and
        // probeDurationMs but INCLUDING agent_health timestamps
        // (lastDetectAt, lastInstallAt — posture evidence).
        String payloadHash = canonicalSha256Hex(hp);

        // Secondary payload-hash deep-equality dedupe pre-probe.
        Optional<EndpointHotfixPostureSnapshot> identical =
                repository.findByTenantDeviceAndPayloadHash(
                        device.getTenantId(), device.getId(), payloadHash,
                        PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Hotfix posture ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();

        EndpointHotfixPostureSnapshot snapshot =
                buildSnapshot(device, commandResultId, hp, payloadHash, collectedAt);

        // BE-024 atomicity + Codex iter-3 P1.1: native targetless
        // ON CONFLICT DO NOTHING.
        UUID insertedId = repository.insertHotfixPostureSnapshotOnConflictDoNothing(snapshot);
        if (insertedId == null) {
            // Targetless ON CONFLICT fired — sequential winner lookup
            // (Codex iter-4 P1): source FIRST when non-null, FALL
            // THROUGH to hash (NOT mutually exclusive), fail loud if
            // neither resolves.
            if (commandResultId != null) {
                Optional<EndpointHotfixPostureSnapshot> bySource =
                        repository.findBySourceCommandResultId(commandResultId);
                if (bySource.isPresent()) {
                    log.debug("Hotfix posture ingest no-op for command_result_id={} (lost source race)",
                            commandResultId);
                    return bySource.get();
                }
            }
            Optional<EndpointHotfixPostureSnapshot> byHash =
                    repository.findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                            device.getTenantId(), device.getId(), payloadHash);
            if (byHash.isPresent()) {
                log.debug("Hotfix posture ingest no-op for device_id={} (lost hash race, snapshot_id={})",
                        device.getId(), byHash.get().getId());
                return byHash.get();
            }
            throw new IllegalStateException(
                    "hotfix posture insert no-op without resolvable winner");
        }

        log.info("Hotfix posture snapshot persisted device_id={} snapshot_id={} schema={} supported={} probeComplete={} installedCount={} pendingTotalCount={}",
                device.getId(), insertedId,
                snapshot.getSchemaVersion(),
                snapshot.getSupported(), snapshot.getProbeComplete(),
                snapshot.getInstalledCount(), snapshot.getPendingTotalCount());
        events.publishEvent(buildAuditEvent(snapshot, command));
        return snapshot;
    }

    public Optional<EndpointHotfixPostureSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    public Page<EndpointHotfixPostureSnapshot> findHistory(
            UUID tenantId, UUID deviceId, Pageable pageable) {
        return repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId, pageable);
    }

    /** Fleet-wide latest snapshot per device for a tenant. Mirrors the
     *  AG-033/AG-036 bulk pattern. */
    @Transactional(readOnly = true)
    public BulkLatestSnapshots<EndpointHotfixPostureSnapshot> findLatestPerDevice(
            UUID tenantId, int cap) {
        List<EndpointHotfixPostureSnapshot> snapshots =
                repository.findLatestPerDeviceForTenant(tenantId, PageRequest.of(0, cap + 1));
        if (snapshots.size() > cap) {
            return BulkLatestSnapshots.overCap();
        }
        return BulkLatestSnapshots.of(snapshots);
    }

    // ------------------------------------------------------------------
    // Canonical hash domain (Codex iter-3 ANSWER + iter-4)
    // ------------------------------------------------------------------

    /**
     * Build the SHA-256 of the canonical-form policy-projected tree.
     *
     * <p>Hash domain — INCLUDE:
     * <ul>
     *   <li>{@code schemaVersion}, {@code supported}, {@code probeComplete}</li>
     *   <li>3 source attributions (installed/pending/health)</li>
     *   <li>Installed: {@code installedHotfixes[]} (with normalized
     *       {@code installedOn} ISO-8601 UTC), {@code installedCount},
     *       {@code installedTruncated}</li>
     *   <li>Pending: {@code pendingUpdates[]} (kbIds + category + severity),
     *       {@code pendingByCategory[]}, {@code pendingTotalCount},
     *       {@code pendingTruncated}</li>
     *   <li>Full normalized {@code agentHealth}: wuaServiceState,
     *       bitsServiceState, lastDetectAt (ISO-8601 UTC), lastInstallAt
     *       (ISO-8601 UTC), nullable AU bools, normalized
     *       notificationLevel</li>
     *   <li>Bounded normalized {@code probeErrors[]}</li>
     * </ul>
     *
     * <p>Hash domain — EXCLUDE:
     * <ul>
     *   <li>Wire {@code collectedAt} (replaced by {@code result.reportedAt}
     *       server-side — timing-only)</li>
     *   <li>{@code probeDurationMs} (timing-only)</li>
     *   <li>Backend ids/timestamps</li>
     * </ul>
     */
    static String canonicalSha256Hex(Map<String, Object> hp) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", hp.get("schemaVersion"));
        canonical.put("supported", hp.get("supported"));
        canonical.put("probeComplete", hp.get("probeComplete"));
        canonical.put("installedSourceUsed", hp.get("installedSourceUsed"));
        canonical.put("installedHotfixes", normalizeInstalledList(hp.get("installedHotfixes")));
        canonical.put("installedCount", hp.get("installedCount"));
        canonical.put("installedTruncated", hp.get("installedTruncated"));
        canonical.put("pendingSourceUsed", hp.get("pendingSourceUsed"));
        canonical.put("pendingUpdates", normalizePendingList(hp.get("pendingUpdates")));
        canonical.put("pendingByCategory", normalizePendingByCategoryList(hp.get("pendingByCategory")));
        canonical.put("pendingTotalCount", hp.get("pendingTotalCount"));
        canonical.put("pendingTruncated", hp.get("pendingTruncated"));
        canonical.put("healthSourceUsed", hp.get("healthSourceUsed"));
        canonical.put("agentHealth", normalizeAgentHealth(hp.get("agentHealth")));
        canonical.put("probeErrors", normalizeProbeErrorsList(hp.get("probeErrors")));
        return sha256Hex(canonical);
    }

    private static List<Object> normalizeInstalledList(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("kbId", m.get("kbId"));
            row.put("installedOn", normalizeInstantValue(m.get("installedOn")));
            row.put("description", m.get("description"));
            out.add(row);
        }
        return out;
    }

    private static List<Object> normalizePendingList(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("kbIds", m.get("kbIds"));
            row.put("primaryCategory", m.get("primaryCategory"));
            row.put("severity", m.get("severity"));
            out.add(row);
        }
        return out;
    }

    private static List<Object> normalizePendingByCategoryList(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("category", m.get("category"));
            row.put("count", m.get("count"));
            out.add(row);
        }
        return out;
    }

    private static List<Object> normalizeProbeErrorsList(Object raw) {
        List<Object> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.get("code"));
            row.put("source", m.get("source"));
            row.put("summary", m.get("summary"));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> normalizeAgentHealth(Object raw) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return out;
        out.put("wuaServiceState", m.get("wuaServiceState"));
        out.put("bitsServiceState", m.get("bitsServiceState"));
        out.put("lastDetectAt", normalizeInstantValue(m.get("lastDetectAt")));
        out.put("lastInstallAt", normalizeInstantValue(m.get("lastInstallAt")));
        out.put("autoUpdatePolicyEnabled", m.get("autoUpdatePolicyEnabled"));
        out.put("autoUpdateEffectiveEnabled", m.get("autoUpdateEffectiveEnabled"));
        out.put("notificationLevel", m.get("notificationLevel"));
        return out;
    }

    /** Normalize a timestamp-shaped value to ISO-8601 UTC string so a
     *  unit test passing an {@link Instant} and a real request carrying a
     *  JSON string produce the same hash (Codex iter-4 implementation
     *  note). */
    static Object normalizeInstantValue(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) {
            return DateTimeFormatter.ISO_INSTANT.format(i);
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return DateTimeFormatter.ISO_INSTANT.format(odt.toInstant());
        }
        if (value instanceof java.time.ZonedDateTime zdt) {
            return DateTimeFormatter.ISO_INSTANT.format(zdt.toInstant());
        }
        if (value instanceof java.util.Date d) {
            return DateTimeFormatter.ISO_INSTANT.format(d.toInstant());
        }
        if (value instanceof CharSequence cs) {
            try {
                Instant parsed = Instant.parse(cs.toString());
                return DateTimeFormatter.ISO_INSTANT.format(parsed);
            } catch (java.time.format.DateTimeParseException ex) {
                // Not a parseable Instant — return as-is (hash will reflect
                // the raw string; downstream contract tests catch malformed).
                return cs.toString();
            }
        }
        return String.valueOf(value);
    }

    private static String sha256Hex(Map<String, Object> canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Deterministic toString() over a LinkedHashMap (mirrors V20
            // approach — change-detection grade, not adversarial integrity).
            byte[] digest = md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ------------------------------------------------------------------
    // Entity tree construction
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractHotfixPosture(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) return null;
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv
                && inv.get("hotfixPosture") instanceof Map<?, ?> hp) {
            return (Map<String, Object>) hp;
        }
        Object top = effectiveDetails.get("hotfixPosture");
        if (top instanceof Map<?, ?> hp) {
            return (Map<String, Object>) hp;
        }
        return null;
    }

    private EndpointHotfixPostureSnapshot buildSnapshot(
            EndpointDevice device, UUID commandResultId,
            Map<String, Object> hp, String payloadHash, Instant collectedAt) {
        EndpointHotfixPostureSnapshot snapshot = new EndpointHotfixPostureSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(requireShort(hp.get("schemaVersion")));
        snapshot.setSupported(requireBool(hp.get("supported")));
        snapshot.setProbeComplete(requireBool(hp.get("probeComplete")));
        snapshot.setInstalledCount(requireInt(hp.get("installedCount")));
        snapshot.setMaxInstalled(HotfixPosturePayloadPolicy.MAX_INSTALLED);
        snapshot.setInstalledTruncated(requireBool(hp.get("installedTruncated")));
        snapshot.setPendingTotalCount(requireInt(hp.get("pendingTotalCount")));
        snapshot.setMaxPending(HotfixPosturePayloadPolicy.MAX_PENDING);
        snapshot.setPendingTruncated(requireBool(hp.get("pendingTruncated")));
        snapshot.setInstalledSourceUsed(requireString(hp.get("installedSourceUsed"),
                "installedSourceUsed"));
        snapshot.setPendingSourceUsed(requireString(hp.get("pendingSourceUsed"),
                "pendingSourceUsed"));
        snapshot.setHealthSourceUsed(requireString(hp.get("healthSourceUsed"),
                "healthSourceUsed"));
        snapshot.setProbeDurationMs((Integer) hp.get("probeDurationMs"));
        snapshot.setPayloadHashSha256(payloadHash);
        snapshot.setCollectedAt(collectedAt);

        // Flat agentHealth scalars at snapshot root.
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        snapshot.setWuaServiceState(requireString(ah.get("wuaServiceState"), "wuaServiceState"));
        snapshot.setBitsServiceState(requireString(ah.get("bitsServiceState"), "bitsServiceState"));
        snapshot.setLastDetectAt(parseInstantOrNull(ah.get("lastDetectAt")));
        snapshot.setLastInstallAt(parseInstantOrNull(ah.get("lastInstallAt")));
        snapshot.setAutoUpdatePolicyEnabled((Boolean) ah.get("autoUpdatePolicyEnabled"));
        snapshot.setAutoUpdateEffectiveEnabled((Boolean) ah.get("autoUpdateEffectiveEnabled"));
        snapshot.setNotificationLevel((String) ah.get("notificationLevel"));

        // Bounded probeErrors (policy already projected, but copy
        // defensively into the canonical JSONB list).
        List<Map<String, Object>> probeErrors = new ArrayList<>();
        Object peRaw = hp.get("probeErrors");
        if (peRaw instanceof List<?> peList) {
            for (Object e : peList) {
                if (e instanceof Map<?, ?> em) {
                    Map<String, Object> row = new HashMap<>();
                    if (em.get("code") != null) row.put("code", String.valueOf(em.get("code")));
                    if (em.get("source") != null) row.put("source", String.valueOf(em.get("source")));
                    if (em.get("summary") != null) {
                        String s = String.valueOf(em.get("summary"));
                        if (s.length() > HotfixPosturePayloadPolicy.SUMMARY_MAX_LEN) {
                            s = s.substring(0, HotfixPosturePayloadPolicy.SUMMARY_MAX_LEN);
                        }
                        row.put("summary", s);
                    }
                    if (!row.isEmpty()) probeErrors.add(row);
                }
            }
        }
        snapshot.setProbeErrors(probeErrors);
        snapshot.setRedactedPayload(new HashMap<>(hp));

        // Installed child facets.
        Object installedRaw = hp.get("installedHotfixes");
        if (installedRaw instanceof List<?> list) {
            int ordinal = 0;
            for (Object rowObj : list) {
                if (rowObj instanceof Map<?, ?> row) {
                    EndpointHotfixPostureInstalled installed = new EndpointHotfixPostureInstalled();
                    installed.setSnapshot(snapshot);
                    installed.setKbId(requireString(row.get("kbId"), "installedHotfixes[].kbId"));
                    installed.setInstalledOn(parseInstantOrNull(row.get("installedOn")));
                    installed.setDescription((String) row.get("description"));
                    installed.setRowOrdinal(ordinal++);
                    snapshot.getInstalledHotfixes().add(installed);
                }
            }
        }

        // Pending child facets + pending_kbs grand-children.
        Object pendingRaw = hp.get("pendingUpdates");
        if (pendingRaw instanceof List<?> list) {
            int ordinal = 0;
            for (Object rowObj : list) {
                if (rowObj instanceof Map<?, ?> row) {
                    EndpointHotfixPosturePending pending = new EndpointHotfixPosturePending();
                    pending.setSnapshot(snapshot);
                    pending.setPrimaryCategory(requireString(row.get("primaryCategory"),
                            "pendingUpdates[].primaryCategory"));
                    pending.setSeverity(requireString(row.get("severity"),
                            "pendingUpdates[].severity"));
                    pending.setRowOrdinal(ordinal++);
                    // pending_kbs grand-children.
                    Object kbIdsRaw = row.get("kbIds");
                    if (kbIdsRaw instanceof List<?> kbIdsList) {
                        int kbOrdinal = 0;
                        for (Object kbObj : kbIdsList) {
                            String kb = requireString(kbObj, "pendingUpdates[].kbIds[]");
                            EndpointHotfixPosturePendingKb kbRow =
                                    new EndpointHotfixPosturePendingKb();
                            kbRow.setPending(pending);
                            kbRow.setKbId(kb);
                            kbRow.setRowOrdinal(kbOrdinal++);
                            pending.getKbs().add(kbRow);
                        }
                    }
                    snapshot.getPendingUpdates().add(pending);
                }
            }
        }

        // pendingByCategory rollup children.
        Object byCategoryRaw = hp.get("pendingByCategory");
        if (byCategoryRaw instanceof List<?> list) {
            int ordinal = 0;
            for (Object rowObj : list) {
                if (rowObj instanceof Map<?, ?> row) {
                    EndpointHotfixPosturePendingCategoryCount cat =
                            new EndpointHotfixPosturePendingCategoryCount();
                    cat.setSnapshot(snapshot);
                    cat.setCategory(requireString(row.get("category"),
                            "pendingByCategory[].category"));
                    Object countObj = row.get("count");
                    if (countObj instanceof Number n) {
                        cat.setCnt(n.intValue());
                    } else {
                        throw new IllegalStateException(
                                "pendingByCategory[].count must be Number after policy projection: " + countObj);
                    }
                    cat.setRowOrdinal(ordinal++);
                    snapshot.getPendingByCategory().add(cat);
                }
            }
        }

        return snapshot;
    }

    private HotfixPostureSnapshotPersistedEvent buildAuditEvent(
            EndpointHotfixPostureSnapshot snapshot, EndpointCommand command) {
        return new HotfixPostureSnapshotPersistedEvent(
                snapshot.getTenantId(),
                snapshot.getDeviceId(),
                snapshot.getId(),
                command != null ? command.getId() : null,
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getInstalledCount(),
                snapshot.getInstalledTruncated(),
                HotfixPostureSnapshotTruncation.isInstalledPossiblyTruncated(snapshot),
                snapshot.getPendingTotalCount(),
                snapshot.getPendingTruncated(),
                HotfixPostureSnapshotTruncation.isPendingPossiblyTruncated(snapshot),
                snapshot.getInstalledSourceUsed(),
                snapshot.getPendingSourceUsed(),
                snapshot.getHealthSourceUsed(),
                snapshot.getPayloadHashSha256(),
                snapshot.getInstalledHotfixes().size(),
                snapshot.getPendingUpdates().size(),
                snapshot.getCollectedAt());
    }

    // ------------------------------------------------------------------
    // Strict required-field accessors (no silent defaults)
    // ------------------------------------------------------------------

    private static int requireInt(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        throw new IllegalStateException(
                "Required integer field null/wrong-type after policy projection: " + value);
    }

    private static short requireShort(Object value) {
        if (value instanceof Number n) {
            int v = n.intValue();
            if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return (short) v;
        }
        throw new IllegalStateException(
                "Required short field null/out-of-range after policy projection: " + value);
    }

    private static boolean requireBool(Object value) {
        if (value instanceof Boolean b) return b;
        throw new IllegalStateException(
                "Required boolean field not Boolean after policy projection: " + value);
    }

    private static String requireString(Object value, String field) {
        if (value instanceof String s && !s.isEmpty()) return s;
        throw new IllegalStateException(
                "Required string field '" + field + "' null/blank after policy projection: " + value);
    }

    private static Instant parseInstantOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof CharSequence cs) {
            try {
                return Instant.parse(cs.toString());
            } catch (java.time.format.DateTimeParseException ex) {
                return null;
            }
        }
        return null;
    }
}
