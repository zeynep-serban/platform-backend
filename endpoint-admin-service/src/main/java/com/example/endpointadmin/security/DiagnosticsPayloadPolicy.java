package com.example.endpointadmin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * BE — pre-persist sanitizer/validator for the diagnostics sub-tree of the
 * agent {@code COLLECT_INVENTORY} payload (Faz 22.5, AG-038-be). Mirrors
 * the AG-037 {@link HotfixPosturePayloadPolicy} strict-allowlist pattern.
 * Wire contract: platform-agent docs/COMMAND-CONTRACT.md §17 (AG-038 PR #39
 * merged 2026-05-30 commit 67bd4ba), source-of-truth =
 * platform-agent internal/inventory/diagnostics.go DiagnosticsResult.
 *
 * <h3>Contract-freeze allowlist</h3>
 *
 * <p>Strict allowlist: any unknown top-level / nested key is fail-closed
 * REJECTED, NOT silently dropped. Wire shape additions require explicit
 * contract bump.
 *
 * <h3>Required vs omitempty (Codex 019e82d7 iter-2 absorb)</h3>
 *
 * <p>TOP_REQUIRED_KEYS=9: {@code schemaVersion, supported, probeComplete,
 * agentVersion, configHash, lastPollLatencyMs, backendDNSReachable,
 * backendTLSValid, probeDurationMs}. The omitempty fields
 * ({@code lastError, probeErrors}) normalize on policy projection:
 * lastError → null, probeErrors → empty list.
 *
 * <h3>Canonical-form payload hash (Codex 019e82d7 iter-3 P1 #4 revise)</h3>
 *
 * <p>SHA-256 over a deterministic LinkedHashMap projection over ALL
 * persistable fields. Iter-3 absorb: lastPollLatencyMs and
 * probeDurationMs are INCLUDED so each fresh observation appends a
 * new snapshot and {@code /latest} reflects the most recent measured
 * latency/duration. Iter-2's "exclude timing" heuristic caused
 * state-A→B→A and latency-only-change cases to leave {@code /latest}
 * stale on the prior snapshot.
 *
 * <ul>
 *   <li><strong>INCLUDED in canonical bytes</strong>: schemaVersion,
 *       supported, probeComplete, agentVersion, configHash (as-is,
 *       lowercase hex or "unknown"), lastPollLatencyMs, probeDurationMs,
 *       backendDNSReachable, backendTLSValid, lastError (full triad with
 *       UTC-normalized occurredAt), probeErrors (ordered list, codes +
 *       summaries).</li>
 *   <li><strong>EXCLUDED</strong> from hash: none. Identical canonical
 *       bytes are retry-idempotent (existing snapshot returned).</li>
 * </ul>
 *
 * <h3>Redaction boundary (security invariant — DO NOT widen)</h3>
 *
 * <p>FORBIDDEN top-level keys (raw apiURL, host, credentialId, token,
 * apiKey, bearer, authorization, cookie, session, secret, password) fail-
 * closed REJECTED even before allowlist check. Summary fields are bounded
 * ≤200 chars + CR/LF/tab/control-char REJECT (policy stricter than DB
 * CHECK which only catches CR/LF per Codex 019e82d7 iter-2 #4).
 *
 * <h3>Empty-string semantics (Codex 019e82d7 iter-2 #5)</h3>
 *
 * <p>{@code probeErrors[i].summary == ""} is REJECT, not normalize-to-null
 * (omitempty means key absent, not empty string). {@code lastError.summary}
 * is required when lastError block is present (triad invariant); empty
 * string is REJECT.
 *
 * <h3>configHash regex (Codex 019e82d7 iter-2 #6)</h3>
 *
 * <p>{@code ^[0-9a-f]{64}$|^unknown$}. Uppercase hex is REJECT, NOT
 * silently normalized — contract is lowercase, backend doesn't fix
 * mis-formatted payloads.
 *
 * <h3>collectedAt payload field (Codex 019e82d7 iter-1 #4)</h3>
 *
 * <p>Payload-level {@code collectedAt} is REJECT (strict-allowlist).
 * Snapshot {@code collectedAt} comes from
 * {@code EndpointCommandResult.reportedAt} (server-controlled).
 */
@Component
public class DiagnosticsPayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsPayloadPolicy.class);

    public static final int SUMMARY_MAX_LEN = 200;
    public static final int PROBE_ERRORS_MAX = 16;
    public static final int AGENT_VERSION_MAX_LEN = 64;

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "agentVersion",
            "configHash",
            "lastPollLatencyMs",
            "backendDNSReachable",
            "backendTLSValid",
            "lastError",
            "probeErrors",
            "probeDurationMs"
    );

    /**
     * Required: scalar fields always sent by the agent. {@code lastError}
     * + {@code probeErrors} are omitempty (agent-side) — missing → null /
     * empty[].
     */
    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "agentVersion",
            "configHash",
            "lastPollLatencyMs",
            "backendDNSReachable",
            "backendTLSValid",
            "probeDurationMs"
    );

    private static final Set<String> LAST_ERROR_ALLOWED_KEYS = Set.of(
            "occurredAt",
            "code",
            "summary"
    );

    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "code",
            "summary"
    );

    /**
     * Explicit forbidden keys — DEFENSE-IN-DEPTH. Any of these at the top
     * level fail-closed REJECTS the payload even if the agent regression
     * accidentally adds one to its wire shape (the allowlist alone would
     * also reject, but the forbidden list emits a clear "credential leak"
     * audit signal).
     */
    private static final Set<String> FORBIDDEN_TOP_KEYS = Set.of(
            "apiURL",
            "apiUrl",
            "apiUrlRaw",
            "rawApiUrl",
            "host",
            "hostname",
            "credentialId",
            "token",
            "apiKey",
            "bearer",
            "authorization",
            "cookie",
            "session",
            "secret",
            "password"
    );

    /**
     * Summary value-level denylist (Codex 019e82d7 iter-3 P1 #2 absorb).
     * Defense-in-depth: even if agent regression accidentally includes
     * a URL / token / authorization header in a static-phrased summary,
     * the policy fail-closed REJECTS the payload. Matches the
     * DeviceHealth/Outdated recursive secret-value reject pattern.
     */
    private static final Pattern SUMMARY_VALUE_DENYLIST_RE = Pattern.compile(
            "(?i)(https?://|bearer\\s|authorization:|x-api-key|api[_-]?key|cookie:|session=|"
                    + "password=|secret=|token=|private[_-]?key|client[_-]?secret|"
                    + "\\.com|\\.net|\\.org|\\.io|\\.local|::ffff:|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    private static final Pattern AGENT_VERSION_RE = Pattern.compile(
            "^(unknown|[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.-]+)?(\\+[A-Za-z0-9.-]+)?)$");

    private static final Pattern CONFIG_HASH_RE = Pattern.compile(
            "^([0-9a-f]{64}|unknown)$");

    private static final Pattern CODE_RE = Pattern.compile(
            "^[A-Z][A-Z0-9_]{2,64}$");

    /**
     * Reject CR / LF / TAB / any other ISO control char in bounded text.
     * Codex 019e82d7 iter-2 #4: policy stricter than DB CHECK ('\\r\\n'
     * only).
     */
    private static final Pattern CONTROL_CHAR_RE = Pattern.compile(
            "[\\x00-\\x1F\\x7F]");

    private final ObjectMapper canonicalMapper;

    public DiagnosticsPayloadPolicy() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = m;
    }

    /**
     * Pre-persist sanitizer hook — matches the AG-037
     * {@code HotfixPosturePayloadPolicy.sanitize(details)} contract.
     * Validates the {@code details.inventory.diagnostics} (and the
     * redundant top-level {@code details.diagnostics}) sub-tree:
     *
     * <ul>
     *   <li>Absent → unchanged.</li>
     *   <li>Present + Map → projected via {@link #projectAndHash(Map)}
     *       (full strict-allowlist enforcement) so any forbidden /
     *       unknown key, type-confusion, redaction breach, regex
     *       violation aborts the SUBMIT-result transaction with 400
     *       BEFORE the result row or any artifact is persisted.</li>
     *   <li>Present + NOT Map (List, String, scalar) → fail-closed
     *       REJECT (Codex 019e82d7 iter-3 P1 #1 absorb: closes the
     *       type-confusion bypass class that would otherwise let the
     *       generic software policy miss AG-038-specific forbidden
     *       keys).</li>
     * </ul>
     *
     * <p>Returns the original details map verbatim; this method does
     * NOT mutate the input. Used purely for validation in the
     * pre-persist chain. The downstream ingest then calls
     * {@link #projectAndHash} again to derive the canonical-form
     * projection + hash; validating twice is cheap and keeps the
     * security boundary independent of the ingest path.
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            Object diagNode = inventoryMap.get("diagnostics");
            validatePresentNode(diagNode, "$.inventory.diagnostics");
        }
        Object topNode = details.get("diagnostics");
        validatePresentNode(topNode, "$.diagnostics");
        return details;
    }

    @SuppressWarnings("unchecked")
    private void validatePresentNode(Object node, String path) {
        if (node == null) {
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics block at " + path
                            + " must be a Map or absent (got " + node.getClass().getName() + ")");
        }
        // Full strict-allowlist validation (forbidden keys, required
        // keys, regex, triad, etc.) — projectAndHash throws on any breach.
        projectAndHash((Map<String, Object>) map);
    }

    /**
     * Validates the diagnostics block and returns a {@link Projection}
     * carrying:
     * <ol>
     *   <li>the LinkedHashMap canonical-form projection (deterministic
     *       insertion order; INCLUDES every persistable field per Codex iter-3 P1 #4 — lastPollLatencyMs
     *       and probeDurationMs);</li>
     *   <li>the SHA-256 lowercase-hex hash of the canonical JSON bytes;</li>
     *   <li>the persistable scalars + child probe-error list (full
     *       projection including lastPollLatencyMs / probeDurationMs for
     *       persistence AND hashing per Codex iter-3 P1 #4 revise).</li>
     * </ol>
     *
     * @throws IllegalArgumentException if the payload is null, the block
     *     is absent, the block is present but not a Map, any unknown key
     *     is present (strict allowlist), any required key is missing, any
     *     forbidden key is present (defense-in-depth), any field value
     *     fails its regex/length/triad rule, or the probe errors array
     *     exceeds the cap.
     */
    public Projection projectAndHash(Map<String, Object> diagnosticsBlock) {
        if (diagnosticsBlock == null) {
            throw new IllegalArgumentException("AG-038 diagnostics block is null.");
        }
        // 1. Forbidden keys (defense-in-depth) — log + reject.
        for (String key : diagnosticsBlock.keySet()) {
            if (FORBIDDEN_TOP_KEYS.contains(key)) {
                log.warn("AG-038 diagnostics payload rejected: forbidden key '{}' present.", key);
                throw new IllegalArgumentException(
                        "AG-038 diagnostics payload contains forbidden key: " + key);
            }
        }
        // 2. Strict-allowlist top-level keys.
        for (String key : diagnosticsBlock.keySet()) {
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics payload contains unknown top-level key: " + key);
            }
        }
        // 3. Required keys (excluding omitempty fields).
        for (String required : TOP_REQUIRED_KEYS) {
            if (!diagnosticsBlock.containsKey(required)) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics payload missing required key: " + required);
            }
        }

        // 4. Scalar parsing + bounded checks.
        int schemaVersion = readInt(diagnosticsBlock, "schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics unsupported schemaVersion: " + schemaVersion);
        }
        boolean supported = readBool(diagnosticsBlock, "supported");
        boolean probeComplete = readBool(diagnosticsBlock, "probeComplete");
        String agentVersion = readString(diagnosticsBlock, "agentVersion");
        validateAgentVersion(agentVersion);
        String configHash = readString(diagnosticsBlock, "configHash");
        validateConfigHash(configHash);
        int lastPollLatencyMs = readInt(diagnosticsBlock, "lastPollLatencyMs");
        if (lastPollLatencyMs < 0) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastPollLatencyMs must be >= 0");
        }
        boolean backendDnsReachable = readBool(diagnosticsBlock, "backendDNSReachable");
        boolean backendTlsValid = readBool(diagnosticsBlock, "backendTLSValid");
        int probeDurationMs = readInt(diagnosticsBlock, "probeDurationMs");
        if (probeDurationMs < 0) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics probeDurationMs must be >= 0");
        }

        // 5. lastError triad (optional, all-or-none).
        LastErrorProjection lastError = projectLastError(
                diagnosticsBlock.getOrDefault("lastError", null));

        // 6. probeErrors list (optional, capped at PROBE_ERRORS_MAX).
        List<ProbeErrorProjection> probeErrors = projectProbeErrors(
                diagnosticsBlock.getOrDefault("probeErrors", null));

        // 7. Canonical-form hash projection (Codex 019e82d7 iter-3 P1 #4
        //    revise: INCLUDES lastPollLatencyMs + probeDurationMs so each
        //    fresh observation appends a new snapshot and /latest always
        //    reflects the most recent measurement).
        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("schemaVersion", schemaVersion);
        hashMap.put("supported", supported);
        hashMap.put("probeComplete", probeComplete);
        hashMap.put("agentVersion", agentVersion);
        hashMap.put("configHash", configHash);
        hashMap.put("lastPollLatencyMs", lastPollLatencyMs);
        hashMap.put("backendDNSReachable", backendDnsReachable);
        hashMap.put("backendTLSValid", backendTlsValid);
        hashMap.put("probeDurationMs", probeDurationMs);
        if (lastError != null) {
            Map<String, Object> leMap = new LinkedHashMap<>();
            leMap.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(lastError.occurredAt()));
            leMap.put("code", lastError.code());
            leMap.put("summary", lastError.summary());
            hashMap.put("lastError", leMap);
        } else {
            hashMap.put("lastError", null);
        }
        List<Map<String, Object>> peList = new ArrayList<>(probeErrors.size());
        for (ProbeErrorProjection pe : probeErrors) {
            Map<String, Object> peMap = new LinkedHashMap<>();
            peMap.put("code", pe.code());
            peMap.put("summary", pe.summary());
            peList.add(peMap);
        }
        hashMap.put("probeErrors", peList);

        String hashHex = sha256Hex(hashMap);

        return new Projection(
                schemaVersion,
                supported,
                probeComplete,
                agentVersion,
                configHash,
                lastPollLatencyMs,
                backendDnsReachable,
                backendTlsValid,
                lastError,
                probeErrors,
                probeDurationMs,
                hashHex);
    }

    private LastErrorProjection projectLastError(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError must be a Map or null.");
        }
        // Strict allowlist + triad.
        for (Object k : rawMap.keySet()) {
            if (!(k instanceof String keyStr) || !LAST_ERROR_ALLOWED_KEYS.contains(keyStr)) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics lastError contains unknown key: " + k);
            }
        }
        for (String required : LAST_ERROR_ALLOWED_KEYS) {
            if (!rawMap.containsKey(required)) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics lastError missing required key: " + required);
            }
        }
        Instant occurredAt = parseInstant(rawMap.get("occurredAt"), "lastError.occurredAt");
        String code = asString(rawMap.get("code"), "lastError.code");
        if (!CODE_RE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError.code violates bounded regex: " + code);
        }
        String summary = asString(rawMap.get("summary"), "lastError.summary");
        if (summary.isEmpty()) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError.summary must be non-empty string.");
        }
        if (summary.length() > SUMMARY_MAX_LEN) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError.summary exceeds length cap.");
        }
        if (CONTROL_CHAR_RE.matcher(summary).find()) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError.summary contains control character.");
        }
        if (SUMMARY_VALUE_DENYLIST_RE.matcher(summary).find()) {
            // Codex 019e82d7 iter-3 P1 #2: summary value-level redaction —
            // raw URL / host / token / authorization / IP / domain reject.
            throw new IllegalArgumentException(
                    "AG-038 diagnostics lastError.summary contains forbidden value pattern (URL/token/host/IP).");
        }
        return new LastErrorProjection(occurredAt, code, summary);
    }

    private List<ProbeErrorProjection> projectProbeErrors(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics probeErrors must be a List or omitted.");
        }
        if (rawList.size() > PROBE_ERRORS_MAX) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics probeErrors exceeds cap of " + PROBE_ERRORS_MAX);
        }
        List<ProbeErrorProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics probeErrors[" + i + "] must be a Map.");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr)
                        || !PROBE_ERROR_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-038 diagnostics probeErrors[" + i
                                    + "] contains unknown key: " + k);
                }
            }
            if (!itemMap.containsKey("code")) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics probeErrors[" + i + "] missing required key: code");
            }
            String code = asString(itemMap.get("code"), "probeErrors[" + i + "].code");
            if (!CODE_RE.matcher(code).matches()) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics probeErrors[" + i
                                + "].code violates bounded regex: " + code);
            }
            String summary = null;
            if (itemMap.containsKey("summary")) {
                Object rawSummary = itemMap.get("summary");
                if (rawSummary != null) {
                    if (!(rawSummary instanceof String s)) {
                        throw new IllegalArgumentException(
                                "AG-038 diagnostics probeErrors[" + i
                                        + "].summary must be a String.");
                    }
                    if (s.isEmpty()) {
                        // Codex 019e82d7 iter-2 #5: "" → REJECT (not
                        // normalize-to-null). omitempty means key absent.
                        throw new IllegalArgumentException(
                                "AG-038 diagnostics probeErrors[" + i
                                        + "].summary must be non-empty (omit the key instead).");
                    }
                    if (s.length() > SUMMARY_MAX_LEN) {
                        throw new IllegalArgumentException(
                                "AG-038 diagnostics probeErrors[" + i
                                        + "].summary exceeds length cap.");
                    }
                    if (CONTROL_CHAR_RE.matcher(s).find()) {
                        throw new IllegalArgumentException(
                                "AG-038 diagnostics probeErrors[" + i
                                        + "].summary contains control character.");
                    }
                    if (SUMMARY_VALUE_DENYLIST_RE.matcher(s).find()) {
                        // Codex 019e82d7 iter-3 P1 #2 absorb.
                        throw new IllegalArgumentException(
                                "AG-038 diagnostics probeErrors[" + i
                                        + "].summary contains forbidden value pattern (URL/token/host/IP).");
                    }
                    summary = s;
                }
            }
            out.add(new ProbeErrorProjection(i, code, summary));
        }
        return out;
    }

    private void validateAgentVersion(String agentVersion) {
        if (agentVersion.length() > AGENT_VERSION_MAX_LEN) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics agentVersion exceeds length cap.");
        }
        if (!AGENT_VERSION_RE.matcher(agentVersion).matches()) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics agentVersion violates bounded regex: " + agentVersion);
        }
    }

    private void validateConfigHash(String configHash) {
        if (!CONFIG_HASH_RE.matcher(configHash).matches()) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics configHash must be 64-char lowercase hex or 'unknown' (got: "
                            + configHash + ")");
        }
    }

    private int readInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics " + key + " is null.");
        }
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return Math.toIntExact(l);
        if (raw instanceof Short s) return s.intValue();
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            int i = n.intValue();
            if (d != i) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics " + key + " must be an integer (got: " + raw + ")");
            }
            return i;
        }
        throw new IllegalArgumentException(
                "AG-038 diagnostics " + key + " must be an integer (got: " + raw.getClass() + ")");
    }

    private boolean readBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics " + key + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-038 diagnostics " + key + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private String readString(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics " + key + " is null.");
        }
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(
                "AG-038 diagnostics " + key + " must be a String (got: " + raw.getClass() + ")");
    }

    private String asString(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics " + label + " is null.");
        }
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(
                "AG-038 diagnostics " + label + " must be a String (got: " + raw.getClass() + ")");
    }

    private Instant parseInstant(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-038 diagnostics " + label + " is null.");
        }
        if (raw instanceof Instant i) return i;
        if (raw instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "AG-038 diagnostics " + label + " is not a valid ISO instant: " + s);
            }
        }
        throw new IllegalArgumentException(
                "AG-038 diagnostics " + label + " must be ISO instant String or Instant.");
    }

    private String sha256Hex(Map<String, Object> canonical) {
        try {
            String json = canonicalMapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "AG-038 diagnostics canonical-form JSON serialization failed.", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    /** Canonical-form projection + hash + scalars. */
    public record Projection(
            int schemaVersion,
            boolean supported,
            boolean probeComplete,
            String agentVersion,
            String configHash,
            int lastPollLatencyMs,
            boolean backendDnsReachable,
            boolean backendTlsValid,
            LastErrorProjection lastError,
            List<ProbeErrorProjection> probeErrors,
            int probeDurationMs,
            String payloadHashSha256) {
    }

    public record LastErrorProjection(Instant occurredAt, String code, String summary) {
    }

    public record ProbeErrorProjection(int rowOrdinal, String code, String summary) {
    }
}
