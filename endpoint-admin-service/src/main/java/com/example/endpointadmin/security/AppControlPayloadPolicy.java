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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — pre-persist sanitizer/validator for the AG-041 Application
 * Control (WDAC + AppLocker) sub-tree of the COLLECT_INVENTORY payload
 * (Faz 22.5, AG-041-be). Mirrors AG-040-be {@link
 * StartupExposurePayloadPolicy} strict-allowlist pattern + adapts to
 * AG-041-specific invariants per Codex 019e840e plan iter-2 AGREE
 * absorb (8 must_fix items).
 *
 * <h3>Top-level contract</h3>
 *
 * <p>20 REQUIRED keys (stable wire shape — Codex iter-2 #1 absorb):
 * schemaVersion (=1), supported, probeComplete, wdacQueryable,
 * appLockerQueryable, wdacMode, wdacBootEnforcementPresent (NULLABLE),
 * wdacActiveCipPolicyCount (NULLABLE), wdacLegacySipolicyPresent
 * (NULLABLE), wdacMultiPolicyMode (NULLABLE), appLockerExeRule,
 * appLockerDllRule, appLockerScriptRule, appLockerMsiRule,
 * appLockerAppxRule, appLockerAppIdSvcState, appLockerAppIdSvcStartup,
 * appLockerAppIdSvcPresent (NULLABLE), probeDurationMs (int 0..120000),
 * probeErrors (List, REQUIRED, empty list accepted, explicit null
 * REJECT).
 *
 * <h3>probeComplete invariant (Codex iter-2 #3 absorb)</h3>
 *
 * <p>{@code probeComplete=true} REQUIRES {@code supported=true AND
 * wdacQueryable=true AND appLockerQueryable=true}. This is the
 * contract-compliant implication the agent actually computes
 * (platform-agent/internal/inventory/app_control_windows.go ProbeComplete
 * derivation). Backend does NOT enforce probeErrors.isEmpty() because
 * non-decision-critical probe errors may coexist with probeComplete=true.
 *
 * <h3>Enum surfaces</h3>
 *
 * <ul>
 *   <li>WdacMode: OFF, AUDIT, ENFORCE, UNKNOWN (UNKNOWN dominant —
 *   Codex iter-1 P0 #2).</li>
 *   <li>AppLockerEnforcement (5 collections): NOT_CONFIGURED, AUDIT_ONLY,
 *   ENFORCE, UNKNOWN.</li>
 *   <li>AppIDSvc state: {@link EndpointServiceWireEnums#SERVICE_STATE_ENUM}
 *   (4 values, V24 mirror).</li>
 *   <li>AppIDSvc startup: {@link EndpointServiceWireEnums#STARTUP_MODE_ENUM}
 *   (5 values including AUTO_DELAYED, V24 mirror).</li>
 *   <li>ProbeError code: 8-value bounded enum.</li>
 *   <li>ProbeError source (optional): wdac, appLocker, filesystem
 *   (lowercase — Codex iter-2 #10).</li>
 * </ul>
 *
 * <h3>Canonical-form payload hash</h3>
 *
 * <p>INCLUDES every persistable scalar with nullable evidence values
 * preserved literally as Java {@code null} in the projection map (Codex
 * iter-2 #6 absorb) + ordered probeErrors. This preserves the wire
 * contract distinction between "evidence not queried" (null) and
 * "evidence absent" (false) at hash time.
 *
 * <h3>Redaction boundary</h3>
 *
 * <p>FORBIDDEN_TOP_KEYS rejects credential vectors + AG-041-specific
 * leak vectors matching the agent's forbidden-substring drift detector
 * (policyName, policyId, policyGuid, policyHash, ruleName, ruleId,
 * publisher, signerThumbprint, commandLine, processName, exePath,
 * filePath, eventLog, kbId).
 */
@Component
public class AppControlPayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(AppControlPayloadPolicy.class);

    public static final int SUMMARY_MAX_LEN = 200;
    public static final int PROBE_DURATION_MAX_MS = 120000;
    public static final int PROBE_ERRORS_MAX = 16;

    /** WDAC mode 4-value enum (Codex 019e840e iter-1 P0 #2: UNKNOWN dominant). */
    public static final Set<String> WDAC_MODE_ENUM = Set.of(
            "OFF", "AUDIT", "ENFORCE", "UNKNOWN"
    );

    /** AppLocker per-collection enforcement 4-value enum. */
    public static final Set<String> APPLOCKER_MODE_ENUM = Set.of(
            "NOT_CONFIGURED", "AUDIT_ONLY", "ENFORCE", "UNKNOWN"
    );

    /** ProbeError code 8-value enum (matches agent AppControlErr* constants). */
    public static final Set<String> PROBE_ERROR_CODE_ENUM = Set.of(
            "NO_EVIDENCE",
            "REGISTRY_DENIED",
            "FILESYSTEM_DENIED",
            "CIP_POLICIES_DIR_UNREADABLE",
            "APPLOCKER_KEY_UNREADABLE",
            "APP_ID_SVC_QUERY_FAILED",
            "WDAC_SCALAR_UNREADABLE",
            "PROBE_ERRORS_TRUNCATED"
    );

    /** ProbeError source 3-value lowercase enum (Codex iter-2 #10 absorb). */
    public static final Set<String> PROBE_ERROR_SOURCE_ENUM = Set.of(
            "wdac", "appLocker", "filesystem"
    );

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete",
            "wdacQueryable", "appLockerQueryable", "wdacMode",
            "wdacBootEnforcementPresent", "wdacActiveCipPolicyCount",
            "wdacLegacySipolicyPresent", "wdacMultiPolicyMode",
            "appLockerExeRule", "appLockerDllRule", "appLockerScriptRule",
            "appLockerMsiRule", "appLockerAppxRule",
            "appLockerAppIdSvcState", "appLockerAppIdSvcStartup",
            "appLockerAppIdSvcPresent",
            "probeDurationMs", "probeErrors"
    );

    // All 20 keys are REQUIRED (stable wire shape — Codex iter-2 #1 + #2).
    private static final Set<String> TOP_REQUIRED_KEYS = TOP_ALLOWED_KEYS;

    /** Nullable evidence keys — explicit null is ACCEPTED (semantic distinct). */
    private static final Set<String> NULLABLE_EVIDENCE_KEYS = Set.of(
            "wdacBootEnforcementPresent",
            "wdacActiveCipPolicyCount",
            "wdacLegacySipolicyPresent",
            "wdacMultiPolicyMode",
            "appLockerAppIdSvcPresent"
    );

    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "code", "source", "summary"
    );

    /**
     * FORBIDDEN top-key set — credential vectors + AG-041 leak vectors
     * matching the agent forbidden-substring drift detector
     * (app_control_test.go TestRedactionBoundary).
     */
    private static final Set<String> FORBIDDEN_TOP_KEYS = Set.of(
            "apiURL", "apiUrl", "host", "hostname", "credentialId",
            "token", "apiKey", "bearer", "authorization", "cookie",
            "session", "secret", "password",
            // AG-041-specific forbidden leak vectors (Codex iter-2 #11).
            "policyName", "policyId", "policyGuid", "policyHash",
            "ruleName", "ruleId", "publisher", "signerThumbprint",
            "commandLine", "processName", "exePath", "filePath",
            "eventLog", "kbId"
    );

    private static final Pattern CONTROL_CHAR_RE = Pattern.compile(
            "[\\x00-\\x1F\\x7F]");

    /** AG-040 SUMMARY_VALUE_DENYLIST_RE reuse — URL/Bearer/IP/token guard. */
    private static final Pattern SUMMARY_VALUE_DENYLIST_RE = Pattern.compile(
            "(?i)(https?://|bearer\\s|authorization:|x-api-key|api[_-]?key|cookie:|session=|"
                    + "password=|secret=|token=|private[_-]?key|client[_-]?secret|"
                    + "\\.com|\\.net|\\.org|\\.io|\\.local|::ffff:|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    private final ObjectMapper canonicalMapper;

    public AppControlPayloadPolicy() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = m;
    }

    /**
     * Pre-persist sanitizer hook — closes the type-confusion bypass
     * class (non-Map appControl value skips this policy → generic
     * software policy misses forbidden keys).
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            validateBlockSlot(inventoryMap, "appControl", "$.inventory.appControl");
        }
        validateBlockSlot(details, "appControl", "$.appControl");
        return details;
    }

    @SuppressWarnings("unchecked")
    private void validateBlockSlot(Map<?, ?> map, String key, String path) {
        if (!map.containsKey(key)) {
            return;
        }
        Object node = map.get(key);
        if (node == null) {
            throw new IllegalArgumentException(
                    "AG-041 appControl block at " + path
                            + " must be a Map or omitted, not null");
        }
        if (!(node instanceof Map<?, ?> mapNode)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl block at " + path
                            + " must be a Map or absent (got " + node.getClass().getName() + ")");
        }
        projectAndHash((Map<String, Object>) mapNode);
    }

    public Projection projectAndHash(Map<String, Object> appControlBlock) {
        if (appControlBlock == null) {
            throw new IllegalArgumentException("AG-041 appControl block is null.");
        }
        // 1. Forbidden top-level keys (defense-in-depth).
        for (String key : appControlBlock.keySet()) {
            if (FORBIDDEN_TOP_KEYS.contains(key)) {
                log.warn("AG-041 appControl payload rejected: forbidden key '{}' present.", key);
                throw new IllegalArgumentException(
                        "AG-041 appControl payload contains forbidden key: " + key);
            }
        }
        // 2. Strict-allowlist top-level keys.
        for (String key : appControlBlock.keySet()) {
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "AG-041 appControl payload contains unknown top-level key: " + key);
            }
        }
        // 3. Required keys (all 20 stable wire keys, Codex iter-2 #2).
        for (String required : TOP_REQUIRED_KEYS) {
            if (!appControlBlock.containsKey(required)) {
                throw new IllegalArgumentException(
                        "AG-041 appControl payload missing required key: " + required);
            }
        }

        // 4. Scalar parsing.
        int schemaVersion = readInt(appControlBlock, "schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl unsupported schemaVersion: " + schemaVersion);
        }
        boolean supported = readBool(appControlBlock, "supported");
        boolean probeComplete = readBool(appControlBlock, "probeComplete");
        boolean wdacQueryable = readBool(appControlBlock, "wdacQueryable");
        boolean appLockerQueryable = readBool(appControlBlock, "appLockerQueryable");
        String wdacMode = readEnum(appControlBlock, "wdacMode", WDAC_MODE_ENUM);

        // Nullable evidence (Codex iter-1 #5 + iter-2 #6).
        Boolean wdacBoot = readNullableBool(appControlBlock, "wdacBootEnforcementPresent");
        Integer wdacCipCount = readNullableNonNegInt(appControlBlock, "wdacActiveCipPolicyCount");
        Boolean wdacLegacySip = readNullableBool(appControlBlock, "wdacLegacySipolicyPresent");
        Boolean wdacMulti = readNullableBool(appControlBlock, "wdacMultiPolicyMode");

        String appExe = readEnum(appControlBlock, "appLockerExeRule", APPLOCKER_MODE_ENUM);
        String appDll = readEnum(appControlBlock, "appLockerDllRule", APPLOCKER_MODE_ENUM);
        String appScript = readEnum(appControlBlock, "appLockerScriptRule", APPLOCKER_MODE_ENUM);
        String appMsi = readEnum(appControlBlock, "appLockerMsiRule", APPLOCKER_MODE_ENUM);
        String appAppx = readEnum(appControlBlock, "appLockerAppxRule", APPLOCKER_MODE_ENUM);
        String appIdState = readEnum(appControlBlock, "appLockerAppIdSvcState",
                EndpointServiceWireEnums.SERVICE_STATE_ENUM);
        String appIdStartup = readEnum(appControlBlock, "appLockerAppIdSvcStartup",
                EndpointServiceWireEnums.STARTUP_MODE_ENUM);
        Boolean appIdPresent = readNullableBool(appControlBlock, "appLockerAppIdSvcPresent");

        int probeDurationMs = readInt(appControlBlock, "probeDurationMs");
        if (probeDurationMs < 0 || probeDurationMs > PROBE_DURATION_MAX_MS) {
            throw new IllegalArgumentException(
                    "AG-041 appControl probeDurationMs out of range [0.." + PROBE_DURATION_MAX_MS
                            + "]: " + probeDurationMs);
        }

        // 5. probeErrors — REQUIRED, List type, explicit null REJECT, non-list REJECT.
        Object probeErrorsRaw = appControlBlock.get("probeErrors");
        if (probeErrorsRaw == null) {
            throw new IllegalArgumentException(
                    "AG-041 appControl probeErrors must be a List (use empty [] for no errors), not null");
        }
        List<ProbeErrorProjection> probeErrors = projectProbeErrors(probeErrorsRaw);

        // 6. probeComplete invariant (Codex iter-2 #3 + iter-3 P2 absorb):
        //    probeComplete=true REQUIRES supported && wdacQueryable
        //    && appLockerQueryable AND no NO_EVIDENCE row in
        //    probeErrors. NO_EVIDENCE is the agent's "overall probe
        //    failed" sentinel (agent non-Windows stub emits it with
        //    supported=false + probeComplete=false), so accepting it
        //    alongside probeComplete=true would persist an internally
        //    contradictory snapshot.
        if (probeComplete && !(supported && wdacQueryable && appLockerQueryable)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl probeComplete=true requires supported && wdacQueryable && appLockerQueryable"
                            + " (got supported=" + supported
                            + ", wdacQueryable=" + wdacQueryable
                            + ", appLockerQueryable=" + appLockerQueryable + ")");
        }
        if (probeComplete) {
            for (ProbeErrorProjection pe : probeErrors) {
                if ("NO_EVIDENCE".equals(pe.code())) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeComplete=true is incompatible with NO_EVIDENCE probe error"
                                    + " (agent contract: NO_EVIDENCE = overall probe failed)");
                }
            }
        }

        // 7. Canonical hash projection — deterministic key order +
        //    nullable evidence values preserved as Java null (Codex
        //    iter-2 #6 absorb).
        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("schemaVersion", schemaVersion);
        hashMap.put("supported", supported);
        hashMap.put("probeComplete", probeComplete);
        hashMap.put("wdacQueryable", wdacQueryable);
        hashMap.put("appLockerQueryable", appLockerQueryable);
        hashMap.put("wdacMode", wdacMode);
        hashMap.put("wdacBootEnforcementPresent", wdacBoot);
        hashMap.put("wdacActiveCipPolicyCount", wdacCipCount);
        hashMap.put("wdacLegacySipolicyPresent", wdacLegacySip);
        hashMap.put("wdacMultiPolicyMode", wdacMulti);
        hashMap.put("appLockerExeRule", appExe);
        hashMap.put("appLockerDllRule", appDll);
        hashMap.put("appLockerScriptRule", appScript);
        hashMap.put("appLockerMsiRule", appMsi);
        hashMap.put("appLockerAppxRule", appAppx);
        hashMap.put("appLockerAppIdSvcState", appIdState);
        hashMap.put("appLockerAppIdSvcStartup", appIdStartup);
        hashMap.put("appLockerAppIdSvcPresent", appIdPresent);
        hashMap.put("probeDurationMs", probeDurationMs);
        List<Map<String, Object>> peList = new ArrayList<>(probeErrors.size());
        for (ProbeErrorProjection pe : probeErrors) {
            Map<String, Object> peMap = new LinkedHashMap<>();
            peMap.put("code", pe.code());
            peMap.put("source", pe.source());
            peMap.put("summary", pe.summary());
            peList.add(peMap);
        }
        hashMap.put("probeErrors", peList);

        String hashHex = sha256Hex(hashMap);

        return new Projection(
                schemaVersion, supported, probeComplete,
                wdacQueryable, appLockerQueryable, wdacMode,
                wdacBoot, wdacCipCount, wdacLegacySip, wdacMulti,
                appExe, appDll, appScript, appMsi, appAppx,
                appIdState, appIdStartup, appIdPresent,
                probeDurationMs, probeErrors, hashHex);
    }

    private List<ProbeErrorProjection> projectProbeErrors(Object raw) {
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl probeErrors must be a List (got "
                            + raw.getClass().getName() + ")");
        }
        if (rawList.size() > PROBE_ERRORS_MAX) {
            throw new IllegalArgumentException(
                    "AG-041 appControl probeErrors exceeds cap of " + PROBE_ERRORS_MAX);
        }
        List<ProbeErrorProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-041 appControl probeErrors[" + i + "] must be a Map.");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr) || !PROBE_ERROR_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "] contains unknown key: " + k);
                }
            }
            if (!itemMap.containsKey("code")) {
                throw new IllegalArgumentException(
                        "AG-041 appControl probeErrors[" + i + "] missing required key: code");
            }
            String code = asString(itemMap.get("code"), "probeErrors[" + i + "].code");
            if (!PROBE_ERROR_CODE_ENUM.contains(code)) {
                throw new IllegalArgumentException(
                        "AG-041 appControl probeErrors[" + i + "].code must be in "
                                + PROBE_ERROR_CODE_ENUM + " got " + code);
            }
            String source = null;
            if (itemMap.containsKey("source")) {
                Object rawSrc = itemMap.get("source");
                if (rawSrc == null) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].source must be omitted, not null");
                }
                if (!(rawSrc instanceof String srcStr)) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].source must be a String.");
                }
                if (srcStr.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].source must be non-empty (omit the key instead)");
                }
                if (!PROBE_ERROR_SOURCE_ENUM.contains(srcStr)) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].source must be in " + PROBE_ERROR_SOURCE_ENUM
                                    + " got " + srcStr);
                }
                source = srcStr;
            }
            String summary = null;
            if (itemMap.containsKey("summary")) {
                Object rawSummary = itemMap.get("summary");
                if (rawSummary == null) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary must be omitted, not null");
                }
                if (!(rawSummary instanceof String s)) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary must be a String.");
                }
                if (s.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary must be non-empty (omit the key instead)");
                }
                if (s.length() > SUMMARY_MAX_LEN) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary exceeds length cap");
                }
                if (CONTROL_CHAR_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary contains control character");
                }
                if (SUMMARY_VALUE_DENYLIST_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-041 appControl probeErrors[" + i + "].summary contains forbidden value pattern (URL/token/host/IP)");
                }
                summary = s;
            }
            out.add(new ProbeErrorProjection(i, code, source, summary));
        }
        return out;
    }

    private int readInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-041 appControl " + key + " is null.");
        }
        if (raw instanceof Integer i) return i;
        if (raw instanceof Short s) return s.intValue();
        if (raw instanceof Long l) {
            try {
                return Math.toIntExact(l);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "AG-041 appControl " + key + " out of int range: " + l, ex);
            }
        }
        throw new IllegalArgumentException(
                "AG-041 appControl " + key + " must be an integer (got: " + raw.getClass() + ")");
    }

    private Integer readNullableNonNegInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            return null;
        }
        int val;
        if (raw instanceof Integer i) val = i;
        else if (raw instanceof Short s) val = s.intValue();
        else if (raw instanceof Long l) {
            try {
                val = Math.toIntExact(l);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "AG-041 appControl " + key + " out of int range: " + l, ex);
            }
        } else {
            throw new IllegalArgumentException(
                    "AG-041 appControl " + key + " must be an integer or null (got: " + raw.getClass() + ")");
        }
        if (val < 0) {
            throw new IllegalArgumentException(
                    "AG-041 appControl " + key + " must be >= 0 (got " + val + ")");
        }
        return val;
    }

    private boolean readBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-041 appControl " + key + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-041 appControl " + key + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private Boolean readNullableBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-041 appControl " + key + " must be a Boolean or null (got: " + raw.getClass() + ")");
    }

    private String readEnum(Map<String, Object> map, String key, Set<String> allowed) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-041 appControl " + key + " is null.");
        }
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl " + key + " must be a String (got: " + raw.getClass() + ")");
        }
        if (!allowed.contains(s)) {
            throw new IllegalArgumentException(
                    "AG-041 appControl " + key + " must be in " + allowed + " got " + s);
        }
        return s;
    }

    private String asString(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-041 appControl " + label + " is null.");
        }
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(
                "AG-041 appControl " + label + " must be a String (got: " + raw.getClass() + ")");
    }

    private String sha256Hex(Map<String, Object> canonical) {
        try {
            String json = canonicalMapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "AG-041 appControl canonical-form JSON serialization failed.", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    public record Projection(
            int schemaVersion,
            boolean supported,
            boolean probeComplete,
            boolean wdacQueryable,
            boolean appLockerQueryable,
            String wdacMode,
            Boolean wdacBootEnforcementPresent,
            Integer wdacActiveCipPolicyCount,
            Boolean wdacLegacySipolicyPresent,
            Boolean wdacMultiPolicyMode,
            String appLockerExeRule,
            String appLockerDllRule,
            String appLockerScriptRule,
            String appLockerMsiRule,
            String appLockerAppxRule,
            String appLockerAppIdSvcState,
            String appLockerAppIdSvcStartup,
            Boolean appLockerAppIdSvcPresent,
            int probeDurationMs,
            List<ProbeErrorProjection> probeErrors,
            String payloadHashSha256) {
    }

    public record ProbeErrorProjection(
            int rowOrdinal,
            String code,
            String source,
            String summary) {
    }
}
