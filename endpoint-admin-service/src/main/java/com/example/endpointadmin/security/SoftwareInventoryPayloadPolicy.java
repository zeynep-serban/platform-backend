package com.example.endpointadmin.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BE-020I — fail-closed PII / sensitive-field policy for the agent
 * {@code COLLECT_INVENTORY} software payload (Faz 22.5.3A).
 *
 * <p>Runs <strong>before</strong> the parent {@code endpoint_command_results}
 * row is persisted (Codex 019e6ab2 iter-2 acceptance #5) so any rejected
 * payload never lands on disk. Failure throws
 * {@link IllegalArgumentException}; the
 * {@code EndpointAgentCommandService.submitResult} caller translates that
 * into a 400 {@link org.springframework.web.server.ResponseStatusException}
 * via the existing handler convention.
 *
 * <p>Forbidden classes (any occurrence anywhere in the payload tree):
 * <ul>
 *   <li><b>Forbidden keys</b>: {@code licenseKey} / {@code productKey} /
 *       {@code uninstallString} / {@code userProfile} / {@code sid} /
 *       {@code bearer} / {@code jwt} / {@code token} / {@code password}
 *       (case-insensitive)</li>
 *   <li><b>Forbidden value patterns</b>: raw MSI ProductCode GUID
 *       ({@code {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}}), absolute
 *       {@code C:\\Users\\...} paths, Windows SID literals
 *       ({@code S-1-5-21-...}). Only the {@code sha256:<16hex>} agent wire
 *       form for the MSI product code hash is accepted.</li>
 * </ul>
 *
 * <p>Allowlist (everything else is accepted as-is — the validator does NOT
 * mutate the payload, it only rejects): the standard summary + apps
 * inventory fields produced by AG-025 / AG-026.
 */
@Component
public class SoftwareInventoryPayloadPolicy {

    private static final java.util.Set<String> FORBIDDEN_KEY_LOWER =
            java.util.Set.of(
                    "licensekey",
                    "productkey",
                    "uninstallstring",
                    "userprofile",
                    "sid",
                    "bearer",
                    "jwt",
                    "token",
                    "password"
            );

    private static final Pattern RAW_MSI_GUID = Pattern.compile(
            "\\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}");

    private static final Pattern USERS_PATH = Pattern.compile(
            "(?i)c:\\\\users\\\\[^\\\\]+");

    private static final Pattern WINDOWS_SID = Pattern.compile(
            "S-1-5-21-\\d+-\\d+-\\d+-\\d+");

    private static final Pattern ACCEPTED_PRODUCT_CODE_HASH = Pattern.compile(
            "^sha256:[0-9a-f]{16}$");

    /**
     * Recursively walks the agent payload tree and throws when any forbidden
     * key or value is observed. The check is structural — both keys (e.g.
     * {@code apps[].licenseKey}) and string values (raw GUID, user path,
     * SID) are rejected.
     *
     * @param payload the agent {@code result_payload.details} map ingested
     *                from {@code POST /commands/{id}/result}
     */
    public void validate(Object payload) {
        walk(payload, "$");
    }

    private void walk(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                String keyLower = rawKey.toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEY_LOWER.contains(keyLower)) {
                    throw new IllegalArgumentException(
                            "Forbidden software-inventory field '"
                                    + rawKey + "' at " + path);
                }
                String childPath = path + "." + rawKey;
                // Special case: msiProductCodeHash must be the sha256:<16hex>
                // wire format. A raw GUID here would also be caught by the
                // string-value scan below, but emitting a precise error helps
                // operators.
                if ("msiproductcodehash".equals(keyLower)
                        && entry.getValue() instanceof String s
                        && !ACCEPTED_PRODUCT_CODE_HASH.matcher(s).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid msiProductCodeHash format at "
                                    + childPath + " (expected sha256:<16hex>).");
                }
                walk(entry.getValue(), childPath);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                walk(element, path + "[" + (i++) + "]");
            }
            return;
        }
        if (node instanceof String s) {
            assertNoRawSecrets(s, path);
        }
        // Numbers, booleans — no leak surface.
    }

    private void assertNoRawSecrets(String value, String path) {
        if (RAW_MSI_GUID.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Raw MSI ProductCode GUID detected at " + path
                            + " — only sha256:<16hex> hashes are accepted.");
        }
        if (USERS_PATH.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Per-user Windows path detected at " + path
                            + " — HKCU / C:\\Users\\... paths are out of scope.");
        }
        if (WINDOWS_SID.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Windows SID literal detected at " + path
                            + " — SIDs must not be shipped in inventory.");
        }
    }
}
