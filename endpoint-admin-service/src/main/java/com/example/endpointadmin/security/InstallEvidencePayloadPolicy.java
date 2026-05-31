package com.example.endpointadmin.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE-021 — DOUBLE-REDACT policy for the agent INSTALL_SOFTWARE
 * {@code details} payload (Faz 22.5). Codex 019e6dfb iter-3 P0-2 / C
 * absorb.
 *
 * <p>The backend cannot trust agent-side redaction. Every install
 * terminal-result submit goes through {@link #validate(Object)} (fail
 * closed on a forbidden key / value) and then through
 * {@link #redact(Map)} (idempotent backend strip + truncate + size cap)
 * before either {@code endpoint_command_results.result_payload.details}
 * or {@code endpoint_install_audit.redacted_payload} is persisted. The
 * raw {@code request.details()} reference never reaches a save call.
 *
 * <p>Idempotency guarantee: {@code redact(redact(x))} produces the same
 * map (forbidden keys are dropped, forbidden value patterns are
 * replaced with the literal {@value #REDACTED_LITERAL} which itself
 * does not match the patterns, and the truncate suffix is sticky).
 */
@Component
public class InstallEvidencePayloadPolicy {

    /** Replacement literal for forbidden value patterns. */
    public static final String REDACTED_LITERAL = "[REDACTED]";
    /** Suffix appended after summary truncate; pattern-stable. */
    public static final String TRUNCATE_SUFFIX = "...[truncated]";
    /** Max bytes of the serialized redacted payload. */
    public static final int MAX_REDACTED_BYTES_DEFAULT = 8 * 1024;
    /** Max bytes per stdoutSummary / stderrSummary entry. */
    public static final int MAX_SUMMARY_BYTES_DEFAULT = 2 * 1024;

    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS = Set.of(
            "stage",
            "exitCode",
            "durationMs",
            "installerVersion",
            "catalogItemId",
            "catalogItemUuid",
            "catalogPackageId",
            // LEGACY flat shape (pre-BE-028 agents / preflight-stage evidence).
            // The canonical install verdict now lives under `details.install`
            // (see below) and `EndpointInstallAuditService.extractDetection`
            // reads ONLY `install`. These flat keys are kept whitelisted so a
            // mixed-version fleet's older payloads are preserved-as-stored
            // rather than silently dropped; they are NOT read for the verdict.
            "detection",
            "postVerification",
            // BE-028: the agent ships the AG-027 InstallResult under
            // `details.install` (COMMAND-CONTRACT §11.2 — "shipped via
            // CommandResult.Details.install"). Without whitelisting `install`
            // the entire result (incl. the authoritative postVerification) was
            // dropped to {} and the audit recorded post_verification=UNKNOWN.
            "install",
            "stdoutSummary",
            "stderrSummary",
            "errorCode",
            "errorMessage"
    );

    /**
     * BE-028: allow-list for the keys INSIDE {@code details.install} (the
     * AG-027 InstallResult wire shape — COMMAND-CONTRACT §11.2). Deliberately
     * MINIMAL — only the install verdict + detection evidence the audit needs.
     * Excluded on purpose (Codex 019e7f93):
     * <ul>
     *   <li>{@code stdoutTail}/{@code stderrTail} — raw installer output. The
     *       backend redactor is weaker than the agent's AG-027L installer/PII
     *       redactor, and "the backend cannot trust agent-side redaction", so
     *       surfacing raw tails through the audit/API would risk leaking
     *       secrets/PII the backend patterns miss. (Follow-up: add an
     *       AG-027L-equivalent backend redactor before surfacing tails.)</li>
     *   <li>{@code egress} — the AG-026A SourceEgressReadiness sub-tree (source
     *       URLs, network targets). It already has a strict schema policy
     *       ({@code WinGetEgressPayloadPolicy}) for the inventory path and is
     *       already exposed via the preflight; the install audit does not need
     *       to re-surface it without that strict validation.</li>
     * </ul>
     * Unknown keys are dropped; forbidden keys / value patterns are still
     * stripped by the recursive {@link #redactValue} pass.
     */
    private static final Set<String> ALLOWED_INSTALL_KEYS = Set.of(
            "finalStatus",
            "schemaVersion",
            "supported",
            "failedReasonCode",
            "exitCode",
            "durationMs",
            "rebootRequired",
            "killStrategy",
            "preDetect",
            "postVerification"
    );

    /**
     * BE-028 (Codex 019e7f93 #2): nested scalar projection for the verdict
     * sub-trees. The agent's {@code PostVerificationResult} wire shape
     * (install_winget.go) carries ONLY {@code satisfied / matchedPackageId /
     * matchedVersion / ruleType}; {@code status} is reserved as a forward-compat
     * 3-way extension. Projecting to a FIXED scalar allow-list means an
     * arbitrary nested key ({@code debugLog}, {@code operatorEmail},
     * {@code clientSecretUrl}, a 20 KiB blob) can never be persisted or surfaced
     * through the admin DTO, and bounds the persisted size. {@code authority} is
     * intentionally NOT here — it is not part of the canonical agent contract.
     */
    private static final Set<String> ALLOWED_POSTVERIFY_KEYS = Set.of(
            "satisfied", "status", "matchedPackageId", "matchedVersion", "ruleType");
    /** {@code PreDetectResult} wire shape (install_winget.go). */
    private static final Set<String> ALLOWED_PREDETECT_KEYS = Set.of(
            "satisfied", "matchedPackageId", "matchedVersion");
    /**
     * Legacy flat {@code details.detection} projection (pre-BE-028 preflight
     * evidence + any agent detection echo). Superset of the predetect scalars
     * plus the legacy {@code packageId/version/type} fields.
     */
    private static final Set<String> ALLOWED_DETECTION_KEYS = Set.of(
            "packageId", "version", "type",
            "satisfied", "status", "matchedPackageId", "matchedVersion", "ruleType");

    /**
     * Keys that are dropped from any nesting depth (case-insensitive).
     * Mirrors {@link SoftwareInventoryPayloadPolicy} plus a few
     * install-specific surface keys.
     */
    private static final Set<String> FORBIDDEN_KEYS_LOWER = Set.of(
            "licensekey",
            "productkey",
            "password",
            "token",
            "jwt",
            "bearer",
            "apikey",
            "secret",
            "envblock",
            "fullcommandline",
            "commandlineraw",
            "processenvironment",
            "stdoutraw",
            "stderrraw"
    );

    private static final Pattern USERS_PATH = Pattern.compile(
            "(?i)c:\\\\users\\\\[^\\\\\\s\"']+");
    private static final Pattern PROGRAMDATA_PATH = Pattern.compile(
            "(?i)c:\\\\programdata\\\\[^\\\\\\s\"']+");
    private static final Pattern WINDOWS_SID = Pattern.compile(
            "S-1-5-21-\\d+-\\d+-\\d+-\\d+");
    private static final Pattern RAW_MSI_GUID = Pattern.compile(
            "\\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}");
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._\\-]+");

    private final int maxRedactedBytes;
    private final int maxSummaryBytes;
    private final ObjectMapper jsonMapper;

    public InstallEvidencePayloadPolicy(
            @Value("${endpoint-admin.installs.max-redacted-bytes:" + MAX_REDACTED_BYTES_DEFAULT + "}")
            int maxRedactedBytes,
            @Value("${endpoint-admin.installs.max-summary-bytes:" + MAX_SUMMARY_BYTES_DEFAULT + "}")
            int maxSummaryBytes) {
        this.maxRedactedBytes = maxRedactedBytes;
        this.maxSummaryBytes = maxSummaryBytes;
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Recursively walk the agent payload tree; throw on any forbidden
     * key or value. The throw rolls back the surrounding
     * {@code EndpointAgentCommandService.submitResult} transaction so
     * neither the result row nor the audit row is persisted.
     */
    public void validate(Object payload) {
        walkValidate(payload, "$");
    }

    /**
     * Produce an idempotent backend-redacted copy of the install
     * payload. Forbidden keys are dropped (case-insensitive); forbidden
     * value patterns are replaced with {@link #REDACTED_LITERAL};
     * {@code stdoutSummary} / {@code stderrSummary} are truncated to
     * {@link #maxSummaryBytes}; the entire serialized payload is
     * trimmed to fit {@link #maxRedactedBytes}. The output is a fresh
     * {@link LinkedHashMap} — the caller's map is not mutated.
     */
    public Map<String, Object> redact(Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (FORBIDDEN_KEYS_LOWER.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!ALLOWED_TOP_LEVEL_KEYS.contains(key)) {
                continue;
            }
            Object redactedValue;
            if ("install".equals(key) && entry.getValue() instanceof Map<?, ?> installMap) {
                // BE-028: the install wrapper carries its own key allow-list.
                redactedValue = redactInstall(installMap);
            } else if ("postVerification".equals(key) && entry.getValue() instanceof Map<?, ?> pv) {
                // Legacy flat postVerification — same scalar projection as the
                // wrapped one (Codex 019e7f93 #2: no raw recursive preserve).
                redactedValue = projectScalars(pv, ALLOWED_POSTVERIFY_KEYS);
            } else if ("detection".equals(key) && entry.getValue() instanceof Map<?, ?> det) {
                redactedValue = projectScalars(det, ALLOWED_DETECTION_KEYS);
            } else {
                redactedValue = redactValue(entry.getValue(), key);
            }
            out.put(key, redactedValue);
        }
        return enforceSizeCap(out);
    }

    /**
     * BE-028: redact {@code details.install} against {@link #ALLOWED_INSTALL_KEYS}.
     * Unknown keys are dropped; recognised values are recursively redacted by
     * {@link #redactValue} (forbidden keys dropped, secret/path value patterns
     * masked) so the authoritative {@code postVerification} / {@code preDetect}
     * / {@code egress} evidence survives while raw command lines and secrets do
     * not.
     */
    private Map<String, Object> redactInstall(Map<?, ?> install) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : install.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (FORBIDDEN_KEYS_LOWER.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!ALLOWED_INSTALL_KEYS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            // BE-028 (Codex 019e7f93 #2): the verdict sub-trees are projected to
            // a fixed scalar allow-list so arbitrary nested payload cannot ride
            // through redactValue's raw recursion into the persisted/surfaced row.
            if ("postVerification".equals(key) && value instanceof Map<?, ?> pv) {
                out.put(key, projectScalars(pv, ALLOWED_POSTVERIFY_KEYS));
            } else if ("preDetect".equals(key) && value instanceof Map<?, ?> pd) {
                out.put(key, projectScalars(pd, ALLOWED_PREDETECT_KEYS));
            } else {
                out.put(key, redactValue(value, key));
            }
        }
        return out;
    }

    /**
     * BE-028 (Codex 019e7f93 #2 + #3): project a verdict sub-tree to a fixed
     * scalar allow-list. Only allow-listed keys survive; nested maps/lists are
     * dropped (the contract carries only scalars here), and string values are
     * still secret/path-masked and summary-capped. This both blocks arbitrary
     * nested key injection and bounds the persisted size of the sub-tree.
     */
    private Map<String, Object> projectScalars(Map<?, ?> node, Set<String> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                continue;
            }
            Object v = entry.getValue();
            if (v instanceof Map<?, ?> || v instanceof Iterable<?>) {
                continue;   // contract carries only scalars in these sub-trees
            }
            if (v instanceof String s) {
                String masked = replaceForbiddenValues(s);
                if (masked.length() > maxSummaryBytes) {
                    masked = masked.substring(0, maxSummaryBytes) + TRUNCATE_SUFFIX;
                }
                out.put(key, masked);
            } else {
                out.put(key, v);
            }
        }
        return out;
    }

    private Object redactValue(Object node, String topKey) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sub = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                if (FORBIDDEN_KEYS_LOWER.contains(rawKey.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                sub.put(rawKey, redactValue(entry.getValue(), topKey));
            }
            return sub;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            for (Object e : iterable) {
                list.add(redactValue(e, topKey));
            }
            return list;
        }
        if (node instanceof String s) {
            String replaced = replaceForbiddenValues(s);
            // BE-028 (Codex 019e7f93 #3): cap the free-text fields that can carry
            // arbitrarily long agent output so the serialized row stays bounded.
            if (("stdoutSummary".equals(topKey) || "stderrSummary".equals(topKey)
                    || "errorMessage".equals(topKey))
                    && replaced.length() > maxSummaryBytes) {
                return replaced.substring(0, maxSummaryBytes) + TRUNCATE_SUFFIX;
            }
            return replaced;
        }
        return node;
    }

    private String replaceForbiddenValues(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = USERS_PATH.matcher(value).replaceAll(REDACTED_LITERAL);
        out = PROGRAMDATA_PATH.matcher(out).replaceAll(REDACTED_LITERAL);
        out = WINDOWS_SID.matcher(out).replaceAll(REDACTED_LITERAL);
        out = RAW_MSI_GUID.matcher(out).replaceAll(REDACTED_LITERAL);
        out = JWT_PATTERN.matcher(out).replaceAll(REDACTED_LITERAL);
        out = BEARER_PATTERN.matcher(out).replaceAll(REDACTED_LITERAL);
        return out;
    }

    private Map<String, Object> enforceSizeCap(Map<String, Object> redacted) {
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(redacted);
            if (bytes.length <= maxRedactedBytes) {
                return redacted;
            }
            // Drop optional decoration fields in priority order until we fit.
            for (String optional : List.of(
                    "stderrSummary", "stdoutSummary", "errorMessage", "errorCode",
                    "installerVersion", "durationMs")) {
                if (redacted.remove(optional) != null) {
                    bytes = jsonMapper.writeValueAsBytes(redacted);
                    if (bytes.length <= maxRedactedBytes) {
                        return redacted;
                    }
                }
            }
            // Collapse the legacy flat detection / postVerification to scalar
            // markers so the row still records that something was reported.
            redacted.computeIfPresent("detection", (k, v) -> Map.of("trimmed", true));
            redacted.computeIfPresent("postVerification", (k, v) -> Map.of("trimmed", true));
            bytes = jsonMapper.writeValueAsBytes(redacted);
            if (bytes.length <= maxRedactedBytes) {
                return redacted;
            }
            // BE-028 (Codex 019e7f93 #3): hard fail-safe. Compact the install
            // wrapper down to the bare verdict (finalStatus + scalar
            // postVerification) so the serialized row is ALWAYS <= the cap, then
            // an absolute last resort that drops install to a marker.
            if (redacted.get("install") instanceof Map<?, ?> inst) {
                Map<String, Object> compact = new LinkedHashMap<>();
                Object finalStatus = inst.get("finalStatus");
                if (finalStatus != null) {
                    compact.put("finalStatus", finalStatus);
                }
                if (inst.get("postVerification") instanceof Map<?, ?> pv) {
                    compact.put("postVerification",
                            projectScalars(pv, ALLOWED_POSTVERIFY_KEYS));
                }
                compact.put("trimmed", true);
                redacted.put("install", compact);
                bytes = jsonMapper.writeValueAsBytes(redacted);
                if (bytes.length <= maxRedactedBytes) {
                    return redacted;
                }
            }
            // Absolute last resort (Codex 019e7f93 #3): a large ALLOWED top-level
            // scalar (catalogPackageId, stage, catalogItemUuid, ...) can still
            // exceed the cap after every field-level degrade. Collapse the entire
            // row to a tiny marker so the persisted payload is ALWAYS
            // <= maxRedactedBytes — the cap is a hard guarantee, not best-effort.
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("trimmed", true);
            marker.put("reason", "redacted_payload_size_cap");
            return marker;
        } catch (JsonProcessingException ex) {
            // Serialization failure: replace with an explicit error marker
            // so the audit row is still written (recordInstallResult cannot
            // succeed without a Map). The validator already passed at this
            // point, so this is a deep-Jackson-edge-case failure.
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("error", "redaction_serialization_failed");
            return fallback;
        }
    }

    // ────────────────────────────────────────────────────────────────
    // validate helpers

    private void walkValidate(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                String keyLower = rawKey.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEYS_LOWER.contains(keyLower)) {
                    throw new IllegalArgumentException(
                            "Forbidden install-evidence field '" + rawKey + "' at " + path);
                }
                walkValidate(entry.getValue(), path + "." + rawKey);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object e : iterable) {
                walkValidate(e, path + "[" + (i++) + "]");
            }
            return;
        }
        if (node instanceof String s) {
            assertNoRawSecrets(s, path);
        }
    }

    private void assertNoRawSecrets(String value, String path) {
        if (WINDOWS_SID.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Windows SID literal detected at " + path
                            + " — SIDs must not be shipped in install evidence.");
        }
        if (JWT_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "JWT detected at " + path
                            + " — install evidence must not carry tokens.");
        }
        // Note: USERS_PATH / PROGRAMDATA_PATH / RAW_MSI_GUID / BEARER are
        // ALLOWED through validate() but masked by redact(); they are
        // common in installer chatter and rejecting them outright would
        // force agents to emit a degraded payload. The redact step strips
        // them before persistence.
    }
}
