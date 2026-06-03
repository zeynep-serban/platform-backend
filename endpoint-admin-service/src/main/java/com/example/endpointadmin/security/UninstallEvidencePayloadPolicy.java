package com.example.endpointadmin.security;

import com.example.endpointadmin.model.UninstallVerification;
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
 * AG-028 Phase 1b — DOUBLE-REDACT policy for the agent
 * {@code UNINSTALL_SOFTWARE} terminal-result {@code details} payload
 * (Faz 22.5.6).
 *
 * <p>Destructive-side counterpart to
 * {@link InstallEvidencePayloadPolicy}. The backend cannot trust
 * agent-side redaction. Every uninstall terminal-result submit goes
 * through {@link #validate(Object)} (fail closed on a forbidden key /
 * value) and then through {@link #redact(Map)} (idempotent backend
 * strip + truncate + size cap) before either
 * {@code endpoint_command_results.result_payload.details} or
 * {@code endpoint_uninstall_audit.redacted_payload} is persisted.
 *
 * <p>Codex Phase 1 plan iter-6 absorb: raw {@code stdoutTail} /
 * {@code stderrTail} are NOT in the allow-list — the backend redactor
 * is weaker than the agent's AG-027L installer/PII redactor and the
 * backend cannot trust agent-side redaction, so surfacing raw tails
 * through the audit/API would risk leaking secrets/PII the backend
 * patterns miss. A follow-up could add an AG-027L-equivalent backend
 * redactor before surfacing tails.
 *
 * <p>Idempotency guarantee: {@code redact(redact(x))} produces the
 * same map (forbidden keys dropped, forbidden value patterns replaced
 * with the literal {@value InstallEvidencePayloadPolicy#REDACTED_LITERAL}
 * which itself does not match the patterns, truncate suffix sticky).
 */
@Component
public class UninstallEvidencePayloadPolicy {

    /** Replacement literal for forbidden value patterns. */
    public static final String REDACTED_LITERAL =
            InstallEvidencePayloadPolicy.REDACTED_LITERAL;
    /** Suffix appended after summary truncate; pattern-stable. */
    public static final String TRUNCATE_SUFFIX =
            InstallEvidencePayloadPolicy.TRUNCATE_SUFFIX;
    /** Max bytes of the serialized redacted payload. */
    public static final int MAX_REDACTED_BYTES_DEFAULT =
            InstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT;
    /** Max bytes per stdoutSummary / stderrSummary / errorMessage. */
    public static final int MAX_SUMMARY_BYTES_DEFAULT =
            InstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT;

    /**
     * Top-level keys in the agent {@code result.details} envelope that
     * are allowed for an UNINSTALL_SOFTWARE terminal result. Keys not
     * in this set are dropped silently by {@link #redact(Map)}.
     *
     * <p>Symmetric with the install allow-list except {@code install}
     * is replaced by {@code uninstall}; the catalog-shaped denormalised
     * fields ({@code catalogItemId}, {@code catalogItemUuid},
     * {@code catalogPackageId}) are surfaced for the audit row.
     */
    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS = Set.of(
            "stage",
            "exitCode",
            "durationMs",
            "catalogItemId",
            "catalogItemUuid",
            "catalogPackageId",
            // AG-028: the agent ships the AG-028 UninstallResult under
            // `details.uninstall` (mirrors the AG-027 install wrapper).
            "uninstall",
            "stdoutSummary",
            "stderrSummary",
            "errorCode",
            "errorMessage");

    /**
     * Keys allowed inside {@code details.uninstall} (the AG-028
     * UninstallResult wire shape). Deliberately MINIMAL.
     *
     * <p>Excluded on purpose:
     * <ul>
     *   <li>{@code stdoutTail}/{@code stderrTail} — raw installer/uninstaller
     *       output. See Codex 019e8d81 iter-2 absorb. A follow-up would
     *       add an AG-027L-equivalent backend redactor before surfacing
     *       tails.</li>
     *   <li>{@code egress} — uninstall does not exercise an egress
     *       readiness sub-tree; would be a leak vector if present.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_UNINSTALL_KEYS = Set.of(
            "finalStatus",
            "schemaVersion",
            "supported",
            "failedReasonCode",
            "exitCode",
            "durationMs",
            "killStrategy",
            "rebootRequired",
            // AG-028: probeState is the absence-aware verification result
            // (MATCHED / ABSENT / PRESENT_MISMATCH / AMBIGUOUS / ERROR /
            // UNSUPPORTED). Drives the {@link UninstallVerification} audit
            // column via {@link #deriveVerification}.
            "probeState",
            "authority",
            "safeEvidence");

    /**
     * Scalar projection allow-list for {@code uninstall.safeEvidence} —
     * a fixed scalar slot list bounds the persisted size and blocks
     * arbitrary nested key injection (mirrors install's
     * ALLOWED_POSTVERIFY_KEYS projection).
     */
    private static final Set<String> ALLOWED_SAFE_EVIDENCE_KEYS = Set.of(
            "ruleType",
            "matchedPackageId",
            "matchedVersion",
            "matchedProductCode",
            "matchedDisplayName",
            "matchedPublisher",
            "candidateCount",
            "absentReason");

    /**
     * Closed allow-list for {@code probeState}. Any other value is
     * normalised to {@code ERROR} by {@link #deriveVerification} so a
     * future agent string drift never accidentally maps to a permissive
     * verdict.
     */
    private static final Set<String> KNOWN_PROBE_STATES = Set.of(
            "MATCHED",
            "ABSENT",
            "PRESENT_MISMATCH",
            "AMBIGUOUS",
            "ERROR",
            "UNSUPPORTED");

    /**
     * Closed allow-list for {@code authority} hint (informational —
     * does not affect the verdict mapping, just kept in the audit
     * payload). Mirrors the install path's authoring authority field.
     */
    private static final Set<String> KNOWN_AUTHORITIES = Set.of(
            "REGISTRY_UNINSTALL",
            "WINGET_PACKAGE",
            "FILE_EXISTS",
            "FILE_SHA256",
            "FILE_VERSION",
            "CONFIRM_ONLY");

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
            "stderrraw",
            "stdouttail",
            "stderrtail");

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

    public UninstallEvidencePayloadPolicy(
            @Value("${endpoint-admin.uninstalls.max-redacted-bytes:"
                    + MAX_REDACTED_BYTES_DEFAULT + "}")
            int maxRedactedBytes,
            @Value("${endpoint-admin.uninstalls.max-summary-bytes:"
                    + MAX_SUMMARY_BYTES_DEFAULT + "}")
            int maxSummaryBytes) {
        this.maxRedactedBytes = maxRedactedBytes;
        this.maxSummaryBytes = maxSummaryBytes;
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * Walk the agent payload tree; throw on any forbidden key or value.
     * The throw rolls back the surrounding agent-submit transaction so
     * neither the result row nor the audit row is persisted.
     */
    public void validate(Object payload) {
        walkValidate(payload, "$");
    }

    /**
     * Produce an idempotent backend-redacted copy of the uninstall
     * payload. Forbidden keys are dropped (case-insensitive); forbidden
     * value patterns are replaced with {@link #REDACTED_LITERAL};
     * {@code stdoutSummary} / {@code stderrSummary} / {@code errorMessage}
     * are truncated to {@link #maxSummaryBytes}; the entire serialized
     * payload is trimmed to fit {@link #maxRedactedBytes}.
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
            if ("uninstall".equals(key) && entry.getValue() instanceof Map<?, ?> uninstallMap) {
                redactedValue = redactUninstall(uninstallMap);
            } else {
                redactedValue = redactValue(entry.getValue(), key);
            }
            out.put(key, redactedValue);
        }
        return enforceSizeCap(out);
    }

    /**
     * Derive the audit {@link UninstallVerification} verdict from the
     * redacted payload. Fail-closed mapping (Codex Phase 1 plan iter-6
     * absorb): an absent / null / unknown {@code probeState} maps to
     * {@link UninstallVerification#VERIFY_INCONCLUSIVE}, NEVER to
     * {@link UninstallVerification#ABSENT_VERIFIED}. This prevents a
     * future agent contract drift from accidentally certifying a
     * destructive operation as successfully absent without authoritative
     * evidence.
     *
     * <ul>
     *   <li>{@code MATCHED} → {@link UninstallVerification#PRESENT_VERIFIED}
     *       (the target package is still detectably present — destructive
     *       op did NOT remove it)</li>
     *   <li>{@code ABSENT} → {@link UninstallVerification#ABSENT_VERIFIED}
     *       (authoritative absence — the destructive op removed it)</li>
     *   <li>{@code PRESENT_MISMATCH} →
     *       {@link UninstallVerification#RESIDUE_PRESENT}</li>
     *   <li>{@code AMBIGUOUS}, {@code ERROR}, {@code UNSUPPORTED},
     *       absent / unknown → {@link UninstallVerification#VERIFY_INCONCLUSIVE}</li>
     * </ul>
     */
    public UninstallVerification deriveVerification(Map<String, Object> redactedDetails) {
        if (redactedDetails == null) {
            return UninstallVerification.VERIFY_INCONCLUSIVE;
        }
        Object uninstallNode = redactedDetails.get("uninstall");
        if (!(uninstallNode instanceof Map<?, ?> uninstallMap)) {
            return UninstallVerification.VERIFY_INCONCLUSIVE;
        }
        Object probeStateNode = uninstallMap.get("probeState");
        if (probeStateNode == null) {
            return UninstallVerification.VERIFY_INCONCLUSIVE;
        }
        String probeState = String.valueOf(probeStateNode).trim()
                .toUpperCase(Locale.ROOT);
        if (!KNOWN_PROBE_STATES.contains(probeState)) {
            return UninstallVerification.VERIFY_INCONCLUSIVE;
        }
        return switch (probeState) {
            case "MATCHED" -> UninstallVerification.PRESENT_VERIFIED;
            case "ABSENT" -> UninstallVerification.ABSENT_VERIFIED;
            case "PRESENT_MISMATCH" -> UninstallVerification.RESIDUE_PRESENT;
            // AMBIGUOUS / ERROR / UNSUPPORTED all map to fail-closed
            default -> UninstallVerification.VERIFY_INCONCLUSIVE;
        };
    }

    /** Project the agent {@code uninstall} sub-tree to the safe allow-list. */
    private Map<String, Object> redactUninstall(Map<?, ?> uninstall) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : uninstall.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (FORBIDDEN_KEYS_LOWER.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!ALLOWED_UNINSTALL_KEYS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if ("safeEvidence".equals(key) && value instanceof Map<?, ?> evidence) {
                out.put(key, projectScalars(evidence, ALLOWED_SAFE_EVIDENCE_KEYS));
            } else if ("authority".equals(key) && value != null) {
                String hint = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
                if (KNOWN_AUTHORITIES.contains(hint)) {
                    out.put(key, hint);
                }
                // Unknown authority drift: dropped silently — verification still
                // resolves from probeState.
            } else if ("probeState".equals(key) && value != null) {
                String probe = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
                if (KNOWN_PROBE_STATES.contains(probe)) {
                    out.put(key, probe);
                }
                // Unknown probeState drift: dropped → deriveVerification returns
                // VERIFY_INCONCLUSIVE (fail-closed).
            } else {
                out.put(key, redactValue(value, key));
            }
        }
        return out;
    }

    private Map<String, Object> projectScalars(Map<?, ?> node, Set<String> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                continue;
            }
            Object v = entry.getValue();
            if (v instanceof Map<?, ?> || v instanceof Iterable<?>) {
                continue;
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
            for (String optional : List.of(
                    "stderrSummary", "stdoutSummary", "errorMessage",
                    "errorCode", "durationMs")) {
                if (redacted.remove(optional) != null) {
                    bytes = jsonMapper.writeValueAsBytes(redacted);
                    if (bytes.length <= maxRedactedBytes) {
                        return redacted;
                    }
                }
            }
            if (redacted.get("uninstall") instanceof Map<?, ?> uni) {
                Map<String, Object> compact = new LinkedHashMap<>();
                Object finalStatus = uni.get("finalStatus");
                if (finalStatus != null) {
                    compact.put("finalStatus", finalStatus);
                }
                Object probe = uni.get("probeState");
                if (probe != null) {
                    compact.put("probeState", probe);
                }
                compact.put("trimmed", true);
                redacted.put("uninstall", compact);
                bytes = jsonMapper.writeValueAsBytes(redacted);
                if (bytes.length <= maxRedactedBytes) {
                    return redacted;
                }
            }
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("trimmed", true);
            marker.put("reason", "redacted_payload_size_cap");
            return marker;
        } catch (JsonProcessingException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("error", "redaction_serialization_failed");
            return fallback;
        }
    }

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
                            "Forbidden uninstall-evidence field '" + rawKey + "' at " + path);
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
                            + " — SIDs must not be shipped in uninstall evidence.");
        }
        if (JWT_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "JWT detected at " + path
                            + " — uninstall evidence must not carry tokens.");
        }
    }
}
