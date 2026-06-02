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
 *       {@link WindowsPathSafetyValidator}. Agent-installable since Path C2
 *       (Codex 019e893a).</li>
 *   <li>{@code FILE_SHA256} — {@code absolutePath} required (path-safety
 *       validated) + {@code expectedSha256} (64-char hex) + optional
 *       {@code maxHashBytes} (≤ 512 MiB agent cap). Agent-installable since
 *       Path C2.</li>
 *   <li>{@code FILE_VERSION} — {@code absolutePath} + {@code versionPredicate}
 *       (LATEST / EXACT / MINIMUM / RANGE) + optional {@code fileVersionField}
 *       (FILE_VERSION default / PRODUCT_VERSION). Agent-installable since
 *       Path C2 (reads PE VersionInfo via Win32 API — Codex 019e893a Opsiyon
 *       C; platform-agent PR #50).</li>
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
            case "FILE_VERSION" -> validateFileVersion(raw);
            default -> throw new IllegalArgumentException(
                    "Detection rule 'type' '" + type + "' is not in the MVP "
                            + "allowlist. Allowed types: WINGET_PACKAGE, "
                            + "REGISTRY_UNINSTALL, FILE_EXISTS, FILE_SHA256, "
                            + "FILE_VERSION. Raw command / shell variants are "
                            + "not accepted.");
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
        // Path C2 — Codex 019e893a Opsiyon C: optional per-rule SHA-256
        // size cap override. The agent (C1) hard-caps at 512 MiB and
        // rejects any value above that; backend mirrors the agent guard
        // so catalog authoring can't ship an impossible cap.
        Object maxHashBytesRaw = raw.get("maxHashBytes");
        if (maxHashBytesRaw != null) {
            long maxHashBytes = asLong(maxHashBytesRaw,
                    "Detection rule 'maxHashBytes' must be an integer.");
            if (maxHashBytes < 0) {
                throw new IllegalArgumentException(
                        "Detection rule 'maxHashBytes' must be >= 0.");
            }
            if (maxHashBytes > FILE_MAX_HASH_BYTES_AGENT) {
                throw new IllegalArgumentException(
                        "Detection rule 'maxHashBytes' (" + maxHashBytes
                                + ") exceeds the agent hard cap ("
                                + FILE_MAX_HASH_BYTES_AGENT + ").");
            }
            normalized.put("maxHashBytes", maxHashBytes);
        }
        return normalized;
    }

    /**
     * Path C2 — FILE_VERSION (Codex 019e893a Opsiyon C). Catalog authoring
     * shape mirrors the agent C1 contract: {@code absolutePath} (Windows
     * path), {@code versionPredicate} ({@code type} ∈ LATEST/EXACT/MINIMUM/
     * RANGE + per-type {@code spec}), {@code fileVersionField} ∈
     * FILE_VERSION (default) / PRODUCT_VERSION.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> validateFileVersion(Map<String, Object> raw) {
        String absolutePath = requireNonBlankString(raw, "absolutePath");
        WindowsPathSafetyValidator.validate(absolutePath);

        Object predicateRaw = raw.get("versionPredicate");
        if (!(predicateRaw instanceof Map<?, ?> predicateMap)) {
            throw new IllegalArgumentException(
                    "Detection rule 'versionPredicate' is required for FILE_VERSION.");
        }
        Map<String, Object> predicate = (Map<String, Object>) predicateMap;
        String predicateType = optionalTrimmed(predicate, "type");
        String predicateSpec = optionalTrimmed(predicate, "spec");
        if (predicateType == null) {
            predicateType = "LATEST";
        }
        String predicateTypeUpper = predicateType.toUpperCase(Locale.ROOT);
        switch (predicateTypeUpper) {
            case "LATEST":
                predicateSpec = null;
                break;
            case "EXACT":
            case "MINIMUM":
                if (predicateSpec == null) {
                    throw new IllegalArgumentException(
                            "Detection rule versionPredicate." + predicateTypeUpper
                                    + " requires a non-blank 'spec'.");
                }
                break;
            case "RANGE":
                if (predicateSpec == null) {
                    throw new IllegalArgumentException(
                            "Detection rule versionPredicate.RANGE requires "
                                    + "a non-blank 'spec'.");
                }
                if (predicateSpec.length() < 3
                        || (predicateSpec.charAt(0) != '['
                            && predicateSpec.charAt(0) != '(')
                        || (predicateSpec.charAt(predicateSpec.length() - 1) != ']'
                            && predicateSpec.charAt(predicateSpec.length() - 1) != ')')
                        || !predicateSpec.contains(",")) {
                    throw new IllegalArgumentException(
                            "Detection rule versionPredicate.RANGE 'spec' must "
                                    + "use bracket form (e.g. '[1.0,2.0)').");
                }
                break;
            default:
                throw new IllegalArgumentException(
                        "Detection rule versionPredicate.type '" + predicateType
                                + "' is not in the allowlist. Allowed: LATEST, "
                                + "EXACT, MINIMUM, RANGE.");
        }

        String field = optionalTrimmed(raw, "fileVersionField");
        if (field == null) {
            field = "FILE_VERSION";
        }
        String fieldUpper = field.toUpperCase(Locale.ROOT);
        if (!"FILE_VERSION".equals(fieldUpper) && !"PRODUCT_VERSION".equals(fieldUpper)) {
            throw new IllegalArgumentException(
                    "Detection rule 'fileVersionField' must be FILE_VERSION or "
                            + "PRODUCT_VERSION (got '" + field + "').");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "FILE_VERSION");
        normalized.put("absolutePath", absolutePath.trim());
        Map<String, Object> normalizedPredicate = new LinkedHashMap<>();
        normalizedPredicate.put("type", predicateTypeUpper);
        if (predicateSpec != null) {
            normalizedPredicate.put("spec", predicateSpec);
        }
        normalized.put("versionPredicate", normalizedPredicate);
        normalized.put("fileVersionField", fieldUpper);
        return normalized;
    }

    /**
     * Path C2 — Codex 019e893a Opsiyon C agent hard cap mirror.
     * 512 MiB hard cap on FILE_SHA256 streaming size.
     */
    static final long FILE_MAX_HASH_BYTES_AGENT = 512L * 1024 * 1024;

    /**
     * Coerce a Number / numeric String to long with INTEGER-ONLY semantics.
     * Codex 019e893a iter-4 P1 absorb: Number.longValue() would silently
     * truncate a fractional value (e.g. 1.5 → 1), but the agent contract
     * is int64. Fractional doubles/floats + non-integer BigDecimals are
     * rejected; numeric strings must match {@code ^-?\d+$}.
     */
    private static long asLong(Object value, String errMessage) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof java.math.BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(errMessage);
            }
        }
        if (value instanceof java.math.BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(errMessage);
            }
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Math.floor(d) != d || Double.isInfinite(d) || Double.isNaN(d)) {
                throw new IllegalArgumentException(errMessage);
            }
            return (long) d;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (!trimmed.matches("-?\\d+")) {
                throw new IllegalArgumentException(errMessage);
            }
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(errMessage);
            }
        }
        throw new IllegalArgumentException(errMessage);
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
