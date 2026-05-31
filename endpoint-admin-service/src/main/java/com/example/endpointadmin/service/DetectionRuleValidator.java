package com.example.endpointadmin.service;

import com.example.endpointadmin.security.WindowsPathSafetyValidator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detection rule validator (Faz 22.5.3 BE-020, reconciled to the AG-027 agent
 * canonical schema in Faz 22.5.4 / agent PR #43 / Codex 019e7d82 verdict A).
 *
 * <p>The catalog stores the detection rule as a JSONB map; this component
 * enforces the polymorphic shape at the service layer. The DB CHECK only
 * guarantees the value is a JSON object with a {@code type} key; everything
 * below is the service-layer contract.
 *
 * <p>MVP allowlist:
 * <ul>
 *   <li>{@code WINGET_PACKAGE} — {@code packageId} required (legacy authoring
 *       field {@code wingetPackageId} accepted on input, stored as
 *       {@code packageId}). Optional {@code wingetSourceName}.</li>
 *   <li>{@code REGISTRY_UNINSTALL} — AUTHORITATIVE Session-0 detector mirroring
 *       the agent ({@code internal/winget/detect_registry.go}
 *       {@code validateRegistryRule}). EXACTLY ONE selector family:
 *       <ul>
 *         <li>{@code productCode} — an MSI GUID {@code {8-4-4-4-12 hex}} (a real
 *             GUID so the agent's direct registry sub-key lookup cannot be fed a
 *             path separator); OR</li>
 *         <li>{@code displayName} + {@code displayNameMatch} ∈ {EXACT, PREFIX,
 *             CONTAINS, GLOB} (GLOB honours only {@code *}/{@code ?} — no regex),
 *             plus {@code publisher} + {@code publisherMatch} ∈ {EXACT, CONTAINS}.
 *             {@code publisher} may be omitted ONLY with
 *             {@code allowPublisherMissing}=true AND an EXACT
 *             {@code displayName}.</li>
 *       </ul>
 *       The deprecated BE-020 shape ({@code hive} / {@code uninstallKeyName} /
 *       {@code displayNameRegex}) is NO LONGER accepted (it never reached the
 *       agent — see {@code EndpointAdminCommandService#buildAgentDetectionRule};
 *       a one-time V21 sweep migrates surviving rows).</li>
 *   <li>{@code FILE_EXISTS} — {@code absolutePath} required, validated by
 *       {@link WindowsPathSafetyValidator}. Valid catalog rule, but NOT yet
 *       agent-installable (rejected at dispatch with a 422).</li>
 *   <li>{@code FILE_SHA256} — {@code absolutePath} required (path-safety
 *       validated) + {@code expectedSha256} (64-char hex). Valid catalog rule,
 *       not yet agent-installable.</li>
 * </ul>
 *
 * <p>Explicitly rejected (raw command injection surface — Codex 019e6a3e
 * iter-1 RED): any {@code EXIT_CODE} / {@code SHELL} / {@code POWERSHELL} /
 * {@code COMMAND} type, or any unknown discriminator.
 */
@Component
public class DetectionRuleValidator {

    static final String MATCH_EXACT = "EXACT";
    static final String MATCH_PREFIX = "PREFIX";
    static final String MATCH_CONTAINS = "CONTAINS";
    static final String MATCH_GLOB = "GLOB";

    private static final int MAX_SELECTOR_LEN = 256;

    public Map<String, Object> validateAndNormalize(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Detection rule is required.");
        }
        Object typeValue = raw.get("type");
        if (!(typeValue instanceof String type) || type.isBlank()) {
            throw new IllegalArgumentException(
                    "Detection rule 'type' must be a non-blank string.");
        }
        return switch (type) {
            case "WINGET_PACKAGE" -> validateWingetPackage(raw);
            case "REGISTRY_UNINSTALL" -> validateRegistryUninstall(raw);
            case "FILE_EXISTS" -> validateFileExists(raw);
            case "FILE_SHA256" -> validateFileSha256(raw);
            default -> throw new IllegalArgumentException(
                    "Detection rule 'type' '" + type + "' is not in the MVP "
                            + "allowlist. Allowed types: WINGET_PACKAGE, "
                            + "REGISTRY_UNINSTALL, FILE_EXISTS, FILE_SHA256. "
                            + "Raw command / shell variants are not accepted.");
        };
    }

    private Map<String, Object> validateWingetPackage(Map<String, Object> raw) {
        // Accept the new field `packageId` or the legacy authoring field
        // `wingetPackageId`; always store the agent-canonical `packageId`.
        String packageId = optionalTrimmed(raw, "packageId");
        if (packageId == null) {
            packageId = optionalTrimmed(raw, "wingetPackageId");
        }
        if (packageId == null) {
            throw new IllegalArgumentException(
                    "Detection rule 'packageId' (or legacy 'wingetPackageId') is "
                            + "required and must be a non-blank string.");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "WINGET_PACKAGE");
        normalized.put("packageId", packageId);
        String source = optionalTrimmed(raw, "wingetSourceName");
        if (source != null) {
            normalized.put("wingetSourceName", source);
        }
        return normalized;
    }

    /**
     * REGISTRY_UNINSTALL — mirrors the agent's {@code validateRegistryRule}
     * (Codex 019e7d82). Fail-closed; the normalized output carries only the
     * agent-recognised keys so {@code buildAgentDetectionRule} can forward it
     * verbatim with no fabrication.
     */
    private Map<String, Object> validateRegistryUninstall(Map<String, Object> raw) {
        String productCode = optionalTrimmed(raw, "productCode");
        String displayName = optionalTrimmed(raw, "displayName");

        // Mirror the agent (validateRegistryRule): productCode takes
        // PRECEDENCE — if present it is the sole selector and displayName is
        // ignored (the agent resolves productCode via a direct registry
        // sub-key lookup). Only when productCode is absent does the
        // displayName(+publisher) fallback apply. Neither present → the rule
        // has no identity (Codex 019e7dce: backend must mirror the agent, not
        // be stricter, so a productCode+displayName pair is accepted, not 400).
        if (productCode == null && displayName == null) {
            throw new IllegalArgumentException(
                    "Detection rule 'REGISTRY_UNINSTALL' requires 'productCode' "
                            + "or 'displayName'.");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "REGISTRY_UNINSTALL");

        if (productCode != null) {
            // productCode is used as a direct registry sub-key name by the
            // agent, so it must be a real MSI GUID (prevents a path separator
            // reaching a nested registry path — Codex 019e7d82). A displayName
            // supplied alongside is intentionally dropped (productCode wins,
            // mirroring the agent).
            if (!isMsiProductCode(productCode)) {
                throw new IllegalArgumentException(
                        "Detection rule 'productCode' must be an MSI GUID of the "
                                + "form {8-4-4-4-12 hex}.");
            }
            normalized.put("productCode", productCode);
            return normalized;
        }

        // DisplayName (+ Publisher) fallback. Length caps are in UTF-8 BYTES to
        // mirror the agent's Go len() (Codex 019e7dce), not Java char count.
        if (utf8Len(displayName) > MAX_SELECTOR_LEN) {
            throw new IllegalArgumentException(
                    "Detection rule 'displayName' is too long (max "
                            + MAX_SELECTOR_LEN + " UTF-8 bytes).");
        }
        String displayNameMatch = normalizeMatchMode(raw, "displayNameMatch");
        switch (displayNameMatch) {
            case MATCH_EXACT, MATCH_PREFIX, MATCH_CONTAINS, MATCH_GLOB -> { }
            default -> throw new IllegalArgumentException(
                    "Detection rule 'displayNameMatch' must be one of EXACT, "
                            + "PREFIX, CONTAINS, GLOB.");
        }
        if (MATCH_GLOB.equals(displayNameMatch)
                && containsAny(displayName, "[]\\")) {
            // GLOB supports only * and ? — reject regex/character-class and
            // path-separator metacharacters (mirrors the agent).
            throw new IllegalArgumentException(
                    "Detection rule glob 'displayName' supports only * and ? "
                            + "(no '[', ']' or '\\').");
        }
        normalized.put("displayName", displayName);
        normalized.put("displayNameMatch", displayNameMatch);

        String publisher = optionalTrimmed(raw, "publisher");
        boolean allowPublisherMissing = asBoolean(raw.get("allowPublisherMissing"));
        if (publisher == null) {
            // Publisher omitted is only safe with an EXACT displayName + the
            // explicit allowPublisherMissing escape hatch (Codex 019e7d82).
            if (!allowPublisherMissing || !MATCH_EXACT.equals(displayNameMatch)) {
                throw new IllegalArgumentException(
                        "Detection rule 'REGISTRY_UNINSTALL' displayName fallback "
                                + "requires 'publisher' (or 'allowPublisherMissing'"
                                + "=true with an EXACT 'displayName').");
            }
            normalized.put("allowPublisherMissing", Boolean.TRUE);
            return normalized;
        }
        if (utf8Len(publisher) > MAX_SELECTOR_LEN) {
            throw new IllegalArgumentException(
                    "Detection rule 'publisher' is too long (max "
                            + MAX_SELECTOR_LEN + " UTF-8 bytes).");
        }
        String publisherMatch = normalizeMatchMode(raw, "publisherMatch");
        switch (publisherMatch) {
            case MATCH_EXACT, MATCH_CONTAINS -> { }
            default -> throw new IllegalArgumentException(
                    "Detection rule 'publisherMatch' must be one of EXACT, "
                            + "CONTAINS.");
        }
        normalized.put("publisher", publisher);
        normalized.put("publisherMatch", publisherMatch);
        return normalized;
    }

    private Map<String, Object> validateFileExists(Map<String, Object> raw) {
        String absolutePath = requireNonBlankString(raw, "absolutePath");
        WindowsPathSafetyValidator.validate(absolutePath);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "FILE_EXISTS");
        normalized.put("absolutePath", absolutePath.trim());
        return normalized;
    }

    private Map<String, Object> validateFileSha256(Map<String, Object> raw) {
        String absolutePath = requireNonBlankString(raw, "absolutePath");
        WindowsPathSafetyValidator.validate(absolutePath);
        String expectedSha256 = requireNonBlankString(raw, "expectedSha256");
        if (expectedSha256.length() != 64
                || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "Detection rule 'expectedSha256' must be a 64-character "
                            + "hex SHA-256 digest.");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "FILE_SHA256");
        normalized.put("absolutePath", absolutePath.trim());
        normalized.put("expectedSha256",
                expectedSha256.toLowerCase(Locale.ROOT));
        return normalized;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * isMsiProductCode mirrors the agent: {@code {XXXXXXXX-XXXX-XXXX-XXXX-
     * XXXXXXXXXXXX}} — 38 chars, case-insensitive hex, dashes at indexes 9, 14,
     * 19, 24, braces at 0 / 37.
     */
    static boolean isMsiProductCode(String s) {
        if (s == null || s.length() != 38 || s.charAt(0) != '{'
                || s.charAt(37) != '}') {
            return false;
        }
        for (int i = 1; i < 37; i++) {
            char c = s.charAt(i);
            if (i == 9 || i == 14 || i == 19 || i == 24) {
                if (c != '-') {
                    return false;
                }
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    /** Uppercased, trimmed match mode; blank/absent defaults to EXACT. */
    private static String normalizeMatchMode(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            return MATCH_EXACT;
        }
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && "true".equalsIgnoreCase(s.trim());
    }

    private static boolean containsAny(String s, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (s.indexOf(chars.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** UTF-8 byte length — mirrors the agent's Go {@code len(string)} (Codex 019e7dce). */
    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Trimmed string value, or {@code null} if absent / not a string / blank. */
    private static String optionalTrimmed(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static String requireNonBlankString(Map<String, Object> raw,
                                                String field) {
        Object value = raw.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "Detection rule '" + field + "' is required and must be "
                            + "a non-blank string.");
        }
        return s.trim();
    }
}
