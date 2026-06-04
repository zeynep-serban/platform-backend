package com.example.endpointadmin.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — pre-persist sanitizer/validator for the AG-039 critical services
 * sub-tree of the COLLECT_INVENTORY payload (Faz 22.5, AG-039-be).
 * Mirrors AG-038-be {@link DiagnosticsPayloadPolicy} strict-allowlist
 * pattern + adapts to services-specific invariants per Codex 019e836c
 * 3-iter AGREE chain.
 *
 * <h3>Top-level contract</h3>
 *
 * <p>5 REQUIRED keys: schemaVersion (=1), supported (bool), probeComplete
 * (bool), services (List), probeDurationMs (int 0..120000). 1 OPTIONAL
 * key with omitempty semantics: probeErrors (List or absent; null REJECT
 * per Codex iter-2 #2).
 *
 * <h3>Exact-six invariant (validate-then-sort, Codex iter-2)</h3>
 *
 * <p>When supported=true: services array MUST have EXACTLY 6 entries whose
 * names form the canonical allowlist SET (each canonical name exactly once).
 * Duplicate / missing / extra REJECT; a valid set in ANY ORDER is ACCEPTED
 * and the backend normalizes it to canonical allowlist order
 * (validate-then-sort). Requiring a positional wire order silently broke
 * live AG-039 ingest (2026-06-04: the agent enumerates in SCM order, BITS
 * before WinDefend, and the result POST was rejected HTTP 400). When
 * supported=false: services array MAY be empty (legitimate non-Windows
 * stub).
 *
 * <h3>Canonical-form payload hash</h3>
 *
 * <p>INCLUDES every persistable field: schemaVersion, supported,
 * probeComplete, services (full ordered list with all 4 fields),
 * probeErrors (ordered list with code + serviceName + summary),
 * probeDurationMs. EXCLUDES: none.
 *
 * <h3>Type-confusion bypass closed</h3>
 *
 * <p>{@link #sanitize(Map)} wired into the pre-persist chain rejects
 * present-but-non-Map services blocks. Jackson containsKey vs
 * get-returns-null distinguishes absent (omitempty ACCEPT) vs explicit
 * null (REJECT) per Codex iter-3 absorb.
 */
@Component
public class ServicesPayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(ServicesPayloadPolicy.class);

    public static final int SUMMARY_MAX_LEN = 200;
    public static final int PROBE_DURATION_MAX_MS = 120000;
    public static final int PROBE_ERRORS_MAX = 16;

    /** Hard-coded v1 canonical service allowlist (Codex 019e8302 iter-2 #1). */
    public static final List<String> CANONICAL_ALLOWLIST = List.of(
            "WinDefend", "wuauserv", "BITS", "EventLog", "EndpointAgent", "MpsSvc"
    );
    public static final Set<String> CANONICAL_ALLOWLIST_SET = Set.copyOf(CANONICAL_ALLOWLIST);

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete",
            "services", "probeErrors", "probeDurationMs"
    );

    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete",
            "services", "probeDurationMs"
    );

    private static final Set<String> ENTRY_ALLOWED_KEYS = Set.of(
            "name", "present", "state", "startupMode"
    );

    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "code", "serviceName", "summary"
    );

    private static final Set<String> SERVICE_STATE_ENUM = Set.of(
            "RUNNING", "STOPPED", "DISABLED", "UNKNOWN"
    );

    private static final Set<String> STARTUP_MODE_ENUM = Set.of(
            "AUTO", "AUTO_DELAYED", "MANUAL", "DISABLED", "UNKNOWN"
    );

    private static final Set<String> PROBE_ERROR_CODE_ENUM = Set.of(
            "UNSUPPORTED_PLATFORM", "SCM_UNAVAILABLE", "SERVICE_NOT_FOUND",
            "SERVICE_QUERY_FAILED", "REGISTRY_QUERY_FAILED", "NO_EVIDENCE"
    );

    private static final Set<String> FORBIDDEN_TOP_KEYS = Set.of(
            "apiURL", "apiUrl", "host", "hostname", "credentialId",
            "token", "apiKey", "bearer", "authorization", "cookie",
            "session", "secret", "password"
    );

    private static final Pattern CONTROL_CHAR_RE = Pattern.compile(
            "[\\x00-\\x1F\\x7F]");

    /** AG-038-be SUMMARY_VALUE_DENYLIST_RE reuse — defense-in-depth
     *  redaction guard against URL / Bearer / IP / token in summary. */
    private static final Pattern SUMMARY_VALUE_DENYLIST_RE = Pattern.compile(
            "(?i)(https?://|bearer\\s|authorization:|x-api-key|api[_-]?key|cookie:|session=|"
                    + "password=|secret=|token=|private[_-]?key|client[_-]?secret|"
                    + "\\.com|\\.net|\\.org|\\.io|\\.local|::ffff:|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    private final ObjectMapper canonicalMapper;

    public ServicesPayloadPolicy() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = m;
    }

    /**
     * Pre-persist sanitizer hook — matches the AG-038-be
     * {@code DiagnosticsPayloadPolicy.sanitize(details)} contract.
     * Closes the type-confusion bypass class (non-Map services value
     * skips this policy → generic software policy misses forbidden
     * keys).
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            Object servicesNode = inventoryMap.get("services");
            validatePresentNode(servicesNode, "$.inventory.services");
        }
        Object topNode = details.get("services");
        validatePresentNode(topNode, "$.services");
        return details;
    }

    @SuppressWarnings("unchecked")
    private void validatePresentNode(Object node, String path) {
        if (node == null) {
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "AG-039 services block at " + path
                            + " must be a Map or absent (got " + node.getClass().getName() + ")");
        }
        projectAndHash((Map<String, Object>) map);
    }

    public Projection projectAndHash(Map<String, Object> servicesBlock) {
        if (servicesBlock == null) {
            throw new IllegalArgumentException("AG-039 services block is null.");
        }
        // 1. Forbidden top-level keys (defense-in-depth).
        for (String key : servicesBlock.keySet()) {
            if (FORBIDDEN_TOP_KEYS.contains(key)) {
                log.warn("AG-039 services payload rejected: forbidden key '{}' present.", key);
                throw new IllegalArgumentException(
                        "AG-039 services payload contains forbidden key: " + key);
            }
        }
        // 2. Strict-allowlist top-level keys.
        for (String key : servicesBlock.keySet()) {
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "AG-039 services payload contains unknown top-level key: " + key);
            }
        }
        // 3. Required keys.
        for (String required : TOP_REQUIRED_KEYS) {
            if (!servicesBlock.containsKey(required)) {
                throw new IllegalArgumentException(
                        "AG-039 services payload missing required key: " + required);
            }
        }

        // 4. Scalar parsing.
        int schemaVersion = readInt(servicesBlock, "schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException(
                    "AG-039 services unsupported schemaVersion: " + schemaVersion);
        }
        boolean supported = readBool(servicesBlock, "supported");
        boolean probeComplete = readBool(servicesBlock, "probeComplete");
        int probeDurationMs = readInt(servicesBlock, "probeDurationMs");
        if (probeDurationMs < 0 || probeDurationMs > PROBE_DURATION_MAX_MS) {
            throw new IllegalArgumentException(
                    "AG-039 services probeDurationMs out of range [0.." + PROBE_DURATION_MAX_MS
                            + "]: " + probeDurationMs);
        }

        // 5. Services validate-then-sort (Codex iter-2).
        List<EntryProjection> entries = projectServices(
                servicesBlock.get("services"), supported);

        // 6. Optional probeErrors (Codex iter-3 #2: absent ACCEPT,
        //    explicit null REJECT, non-List REJECT).
        List<ProbeErrorProjection> probeErrors = projectProbeErrors(
                servicesBlock.containsKey("probeErrors")
                        ? servicesBlock.get("probeErrors") : null,
                /*explicitlyPresent*/ servicesBlock.containsKey("probeErrors"));

        // 7. Canonical hash projection.
        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("schemaVersion", schemaVersion);
        hashMap.put("supported", supported);
        hashMap.put("probeComplete", probeComplete);
        hashMap.put("probeDurationMs", probeDurationMs);
        List<Map<String, Object>> serviceList = new ArrayList<>(entries.size());
        for (EntryProjection e : entries) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.name());
            em.put("present", e.present());
            em.put("state", e.state());
            em.put("startupMode", e.startupMode());
            serviceList.add(em);
        }
        hashMap.put("services", serviceList);
        List<Map<String, Object>> peList = new ArrayList<>(probeErrors.size());
        for (ProbeErrorProjection pe : probeErrors) {
            Map<String, Object> peMap = new LinkedHashMap<>();
            peMap.put("code", pe.code());
            peMap.put("serviceName", pe.serviceName());
            peMap.put("summary", pe.summary());
            peList.add(peMap);
        }
        hashMap.put("probeErrors", peList);

        String hashHex = sha256Hex(hashMap);

        return new Projection(
                schemaVersion, supported, probeComplete, probeDurationMs,
                entries, probeErrors, hashHex);
    }

    @SuppressWarnings("unchecked")
    private List<EntryProjection> projectServices(Object raw, boolean supported) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-039 services list is null (must be array).");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-039 services must be a List (got " + raw.getClass().getName() + ")");
        }
        if (!supported) {
            // Non-Windows / unsupported: services list MAY be empty.
            if (!rawList.isEmpty()) {
                throw new IllegalArgumentException(
                        "AG-039 services list MUST be empty when supported=false; got "
                                + rawList.size() + " entries");
            }
            return List.of();
        }
        // supported=true: exact-six invariant + canonical order.
        if (rawList.size() != CANONICAL_ALLOWLIST.size()) {
            throw new IllegalArgumentException(
                    "AG-039 services list MUST have exactly "
                            + CANONICAL_ALLOWLIST.size()
                            + " entries when supported=true; got " + rawList.size());
        }
        List<EntryProjection> out = new ArrayList<>(CANONICAL_ALLOWLIST.size());
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-039 services[" + i + "] must be a Map (got "
                                + (item == null ? "null" : item.getClass().getName()) + ")");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr) || !ENTRY_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-039 services[" + i + "] contains unknown key: " + k);
                }
            }
            for (String required : ENTRY_ALLOWED_KEYS) {
                if (!itemMap.containsKey(required)) {
                    throw new IllegalArgumentException(
                            "AG-039 services[" + i + "] missing required key: " + required);
                }
            }
            String name = asString(itemMap.get("name"), "services[" + i + "].name");
            // validate-then-sort (class doc): canonical-SET membership, NOT
            // positional order. The agent enumerates the 6 services in SCM /
            // probe order (observed live: BITS before WinDefend), which is a
            // valid set but a different order; the previous positional check
            // (expected == CANONICAL_ALLOWLIST.get(i)) rejected it with HTTP
            // 400 ("services[0].name violates canonical order"), silently
            // breaking AG-039 ingest end-to-end. Canonical ORDER is a
            // backend-owned normalization applied after the loop, not an agent
            // wire contract. exact-six + membership + no-duplicate together
            // prove the set is exactly the canonical six.
            if (!CANONICAL_ALLOWLIST_SET.contains(name)) {
                throw new IllegalArgumentException(
                        "AG-039 services[" + i + "].name not in canonical allowlist "
                                + CANONICAL_ALLOWLIST + "; got " + name);
            }
            if (!seenNames.add(name)) {
                throw new IllegalArgumentException(
                        "AG-039 services[" + i + "].name duplicate (canonical set requires each "
                                + "name exactly once): " + name);
            }
            boolean present = asBool(itemMap.get("present"), "services[" + i + "].present");
            String state = asString(itemMap.get("state"), "services[" + i + "].state");
            if (!SERVICE_STATE_ENUM.contains(state)) {
                throw new IllegalArgumentException(
                        "AG-039 services[" + i + "].state must be in " + SERVICE_STATE_ENUM
                                + " got " + state);
            }
            String startupMode = asString(itemMap.get("startupMode"), "services[" + i + "].startupMode");
            if (!STARTUP_MODE_ENUM.contains(startupMode)) {
                throw new IllegalArgumentException(
                        "AG-039 services[" + i + "].startupMode must be in " + STARTUP_MODE_ENUM
                                + " got " + startupMode);
            }
            out.add(new EntryProjection(i, name, present, state, startupMode));
        }
        // exact-six + all-members + no-duplicate ⟹ exactly the canonical six.
        // Normalize to canonical order with a canonical rowOrdinal (0..5) so the
        // payload hash + persisted rows are deterministic regardless of the
        // agent's enumeration order.
        out.sort((a, b) -> Integer.compare(
                CANONICAL_ALLOWLIST.indexOf(a.name()), CANONICAL_ALLOWLIST.indexOf(b.name())));
        List<EntryProjection> ordered = new ArrayList<>(out.size());
        for (int c = 0; c < out.size(); c++) {
            EntryProjection e = out.get(c);
            ordered.add(new EntryProjection(c, e.name(), e.present(), e.state(), e.startupMode()));
        }
        return ordered;
    }

    private List<ProbeErrorProjection> projectProbeErrors(Object raw, boolean explicitlyPresent) {
        if (!explicitlyPresent) {
            // omitempty path — field absent, normalize to empty list.
            return List.of();
        }
        if (raw == null) {
            // Codex iter-3 #2: explicit null REJECT.
            throw new IllegalArgumentException(
                    "AG-039 services probeErrors must be a List or omitted, not null");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-039 services probeErrors must be a List (got "
                            + raw.getClass().getName() + ")");
        }
        if (rawList.size() > PROBE_ERRORS_MAX) {
            throw new IllegalArgumentException(
                    "AG-039 services probeErrors exceeds cap of " + PROBE_ERRORS_MAX);
        }
        List<ProbeErrorProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-039 services probeErrors[" + i + "] must be a Map.");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr) || !PROBE_ERROR_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "] contains unknown key: " + k);
                }
            }
            if (!itemMap.containsKey("code")) {
                throw new IllegalArgumentException(
                        "AG-039 services probeErrors[" + i + "] missing required key: code");
            }
            String code = asString(itemMap.get("code"), "probeErrors[" + i + "].code");
            if (!PROBE_ERROR_CODE_ENUM.contains(code)) {
                throw new IllegalArgumentException(
                        "AG-039 services probeErrors[" + i + "].code must be in "
                                + PROBE_ERROR_CODE_ENUM + " got " + code);
            }
            String serviceName = null;
            if (itemMap.containsKey("serviceName")) {
                Object rawSn = itemMap.get("serviceName");
                if (rawSn == null) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].serviceName must be omitted, not null");
                }
                if (!(rawSn instanceof String snStr)) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].serviceName must be a String.");
                }
                if (snStr.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].serviceName must be non-empty (omit the key instead)");
                }
                if (!CANONICAL_ALLOWLIST_SET.contains(snStr)) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].serviceName must be in canonical allowlist; got " + snStr);
                }
                serviceName = snStr;
            }
            String summary = null;
            if (itemMap.containsKey("summary")) {
                Object rawSummary = itemMap.get("summary");
                if (rawSummary == null) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary must be omitted, not null");
                }
                if (!(rawSummary instanceof String s)) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary must be a String.");
                }
                if (s.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary must be non-empty (omit the key instead)");
                }
                if (s.length() > SUMMARY_MAX_LEN) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary exceeds length cap");
                }
                if (CONTROL_CHAR_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary contains control character");
                }
                if (SUMMARY_VALUE_DENYLIST_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-039 services probeErrors[" + i + "].summary contains forbidden value pattern (URL/token/host/IP)");
                }
                summary = s;
            }
            out.add(new ProbeErrorProjection(i, code, serviceName, summary));
        }
        return out;
    }

    private int readInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-039 services " + key + " is null.");
        }
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return Math.toIntExact(l);
        if (raw instanceof Short s) return s.intValue();
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            int iv = n.intValue();
            if (d != iv) {
                throw new IllegalArgumentException(
                        "AG-039 services " + key + " must be an integer (got: " + raw + ")");
            }
            return iv;
        }
        throw new IllegalArgumentException(
                "AG-039 services " + key + " must be an integer (got: " + raw.getClass() + ")");
    }

    private boolean readBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-039 services " + key + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-039 services " + key + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private String asString(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-039 services " + label + " is null.");
        }
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(
                "AG-039 services " + label + " must be a String (got: " + raw.getClass() + ")");
    }

    private boolean asBool(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-039 services " + label + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-039 services " + label + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private String sha256Hex(Map<String, Object> canonical) {
        try {
            String json = canonicalMapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "AG-039 services canonical-form JSON serialization failed.", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    public record Projection(
            int schemaVersion,
            boolean supported,
            boolean probeComplete,
            int probeDurationMs,
            List<EntryProjection> services,
            List<ProbeErrorProjection> probeErrors,
            String payloadHashSha256) {
    }

    public record EntryProjection(
            int rowOrdinal,
            String name,
            boolean present,
            String state,
            String startupMode) {
    }

    public record ProbeErrorProjection(
            int rowOrdinal,
            String code,
            String serviceName,
            String summary) {
    }
}
