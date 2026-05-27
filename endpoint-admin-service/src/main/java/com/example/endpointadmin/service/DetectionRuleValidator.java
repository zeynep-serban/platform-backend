package com.example.endpointadmin.service;

import com.example.endpointadmin.security.WindowsPathSafetyValidator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BE-020 detection rule validator (Faz 22.5.3).
 *
 * <p>The catalog stores the detection rule as a JSONB map; this component
 * enforces the polymorphic shape at the service layer. The DB CHECK only
 * guarantees the value is a JSON object with a {@code type} key; everything
 * below is the service-layer contract.
 *
 * <p>MVP allowlist (Codex 019e6a3e iter-2 acceptance #4):
 * <ul>
 *   <li>{@code WINGET_PACKAGE} — {@code wingetPackageId} required.</li>
 *   <li>{@code REGISTRY_UNINSTALL} — {@code hive} (HKLM / HKLM_WOW6432) +
 *       {@code uninstallKeyName} required.</li>
 *   <li>{@code FILE_EXISTS} — {@code absolutePath} required, validated by
 *       {@link WindowsPathSafetyValidator}.</li>
 *   <li>{@code FILE_SHA256} — {@code absolutePath} required (path-safety
 *       validated) + {@code expectedSha256} (64-char hex).</li>
 * </ul>
 *
 * <p>Explicitly rejected (raw command injection surface — Codex 019e6a3e
 * iter-1 RED): any {@code EXIT_CODE} / {@code SHELL} / {@code POWERSHELL} /
 * {@code COMMAND} type, or any unknown discriminator.
 */
@Component
public class DetectionRuleValidator {

    private static final Set<String> ALLOWED_REGISTRY_HIVES =
            Set.of("HKLM", "HKLM_WOW6432");

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
        String packageId = requireNonBlankString(raw, "wingetPackageId");
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "WINGET_PACKAGE");
        normalized.put("wingetPackageId", packageId);
        Object source = raw.get("wingetSourceName");
        if (source instanceof String s && !s.isBlank()) {
            normalized.put("wingetSourceName", s.trim());
        }
        return normalized;
    }

    private Map<String, Object> validateRegistryUninstall(Map<String, Object> raw) {
        String hive = requireNonBlankString(raw, "hive");
        if (!ALLOWED_REGISTRY_HIVES.contains(hive)) {
            throw new IllegalArgumentException(
                    "Detection rule 'hive' must be one of "
                            + ALLOWED_REGISTRY_HIVES + ".");
        }
        String uninstallKeyName = requireNonBlankString(raw, "uninstallKeyName");
        if (uninstallKeyName.contains("\\") || uninstallKeyName.contains("/")) {
            throw new IllegalArgumentException(
                    "Detection rule 'uninstallKeyName' must be a single "
                            + "registry leaf name without path separators.");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", "REGISTRY_UNINSTALL");
        normalized.put("hive", hive);
        normalized.put("uninstallKeyName", uninstallKeyName);
        Object displayNameRegex = raw.get("displayNameRegex");
        if (displayNameRegex instanceof String s && !s.isBlank()) {
            normalized.put("displayNameRegex", s);
        }
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
