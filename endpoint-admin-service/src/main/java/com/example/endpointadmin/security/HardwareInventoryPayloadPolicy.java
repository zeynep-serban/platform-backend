package com.example.endpointadmin.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE-022 — pre-persist sanitizer/redactor for the hardware sub-tree of
 * the agent {@code COLLECT_INVENTORY} payload (Faz 22.5). Codex
 * {@code 019e7007} iter-4 absorb.
 *
 * <p>This policy differs from {@link SoftwareInventoryPayloadPolicy}
 * in two important ways:
 *
 * <ol>
 *   <li><b>It mutates</b> — instead of fail-closed rejecting every
 *       sensitive value, it strips hardware-identifying values
 *       (BIOS / disk serials, user paths, Windows SIDs, machine GUIDs)
 *       to {@code "<redacted>"} so the rest of the snapshot can still
 *       be persisted. Tokens / passwords / JWT / bearer values are
 *       still fail-closed rejected — those have no legitimate place
 *       in a hardware payload.</li>
 *   <li><b>It runs first</b> — the agent SUBMIT-result hook calls
 *       {@code HardwareInventoryPayloadPolicy.sanitize(details)}
 *       <strong>before</strong>
 *       {@code SoftwareInventoryPayloadPolicy.validate(effectiveDetails)}
 *       so the software policy sees the redacted form (Codex
 *       {@code 019e7007} iter-3 must-fix). Otherwise the software
 *       validator's path / SID / GUID rejection would fire on hardware
 *       facts the operator legitimately wants captured (in redacted
 *       form).</li>
 * </ol>
 *
 * <p>Scope: this policy only touches
 * {@code details.inventory.hardware} (and the redundant top-level
 * {@code details.hardware} accepted by some agent versions). The
 * software sub-tree under {@code details.inventory.software} is
 * untouched — the existing software policy remains the authority for
 * it.
 *
 * <p>Schema validation: {@link #sanitize(Map)} additionally validates
 * the schema version, parses {@code collectedAt}, and normalizes MAC
 * addresses to lowercase canonical form. Invalid sub-shapes
 * (non-array {@code disks} / {@code networkInterfaces}, non-numeric
 * ranges) fail fast with {@link IllegalArgumentException}.
 */
@Component
public class HardwareInventoryPayloadPolicy {

    /**
     * Keys whose value, anywhere in the hardware sub-tree, must cause
     * a fail-closed reject. Tokens / passwords / JWTs have no
     * legitimate hardware presence — observing one means the agent
     * (or an intermediary) is shipping something it shouldn't.
     */
    private static final Set<String> REJECT_KEY_LOWER = Set.of(
            "token",
            "bearer",
            "jwt",
            "password",
            "secret"
    );

    /**
     * Keys whose value is stripped (replaced with {@value #REDACTED})
     * rather than rejected — hardware identifiers that we want to
     * acknowledge ("yes, BIOS reported a serial") without retaining
     * the value.
     */
    private static final Set<String> STRIP_KEY_LOWER = Set.of(
            "biosserial",
            "biosserialnumber",
            "serialnumber",
            "diskserial",
            "diskserialnumber",
            "uuid",
            "machineguid",
            "computersid",
            "sid",
            "userpath",
            "userdir",
            "homedir"
    );

    /** Value pattern matchers — applied to every string scalar in the
     * hardware tree, regardless of key name. */
    private static final Pattern USERS_PATH = Pattern.compile(
            "(?i)c:\\\\users\\\\[^\\\\]+");

    private static final Pattern UNIX_USER_PATH = Pattern.compile(
            "/(home|Users)/[^/]+");

    private static final Pattern WINDOWS_SID = Pattern.compile(
            "S-1-5-21-\\d+-\\d+-\\d+-\\d+");

    /** Braced machine GUID — `{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}`. */
    private static final Pattern MACHINE_GUID = Pattern.compile(
            "(?i)\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}");

    /**
     * Value-level secret pattern denylist (Codex 019e7007 post-impl
     * iter-1 must-fix #3). Key-level reject covers
     * {@code token / password / jwt / bearer / secret}, but raw
     * agent-reported strings can also leak secrets out of band — e.g.
     * {@code probeErrors[].summary = "auth header rejected: Bearer eyJ..."}.
     * The patterns below fail-closed reject the result entire so the
     * snapshot, the command result, and any audit copy of the payload
     * all roll back together.
     */
    private static final Pattern[] SECRET_VALUE_PATTERNS = new Pattern[]{
            // Bearer / Basic auth schemes followed by an opaque token.
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._=+/-]{10,}"),
            // Compact JWT: three base64url segments separated by dots,
            // with the canonical `eyJ` (base64 of `{"`) header prefix.
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            // Common `password=...`, `secret=...`, `api_key=...` kv
            // leaks in agent error / probe strings.
            Pattern.compile("(?i)\\b(password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token)\\s*[=:]\\s*[^\\s]{4,}")
    };

    /** Canonical lowercase MAC matcher. */
    private static final Pattern MAC_ANY = Pattern.compile(
            "(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}");

    private static final Pattern MAC_LOWERCASE_COLON = Pattern.compile(
            "[0-9a-f]{2}(:[0-9a-f]{2}){5}");

    public static final String REDACTED = "<redacted>";

    /** Acceptable {@code schema_version} values (current agent emits 1). */
    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    /**
     * Walk {@code details} producing a new map with the hardware
     * sub-tree sanitized. The original map is not modified.
     *
     * @param details the agent {@code result_payload.details} map
     * @return sanitized {@code effectiveDetails} — caller hands this
     *         (a) to {@code SoftwareInventoryPayloadPolicy.validate()},
     *         (b) to the persisted {@code result_payload}, and
     *         (c) to the hardware ingest hook
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Map<String, Object> sanitized = deepCopyMap(details);
        Object inventoryNode = sanitized.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) inventoryMap;
            // Fail-closed reject a present-but-non-Map hardware block (a
            // List / String / scalar) BEFORE the Map-gated sanitize. A
            // non-Map value would otherwise skip the serial-STRIP /
            // MAC-normalization redaction AND the Map-only ingest gate, so
            // a raw serial / unnormalized MAC carried in a hardware list
            // would leak into endpoint_command_results.result_payload.details
            // with no 400. Absent / explicit-null stays the valid opt-out.
            // Mirrors WinGetEgressPayloadPolicy.validate's non-Map reject.
            Object hardwareNode = requireMapOrAbsent(
                    typed.get("hardware"), "$.inventory.hardware");
            if (hardwareNode != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> hwTyped = (Map<String, Object>) hardwareNode;
                Map<String, Object> sanitizedHw = sanitizeHardware(hwTyped, "$.inventory.hardware");
                typed.put("hardware", sanitizedHw);
            }
        }
        // Some agent versions also place hardware at the top level —
        // sanitize that too if present. Same fail-closed reject: a
        // present-but-non-Map top-level hardware alias must not bypass
        // the redaction boundary.
        Object topHardware = requireMapOrAbsent(
                sanitized.get("hardware"), "$.hardware");
        if (topHardware != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hwTyped = (Map<String, Object>) topHardware;
            sanitized.put("hardware", sanitizeHardware(hwTyped, "$.hardware"));
        }
        return sanitized;
    }

    /**
     * Fail-closed map-shape gate for the hardware block at a
     * redaction-boundary entry point. Returns {@code null} for an absent /
     * explicit-null value (the valid opt-out), the value itself when it is
     * a {@link Map}, and throws {@link IllegalArgumentException} (→ 400) for
     * any present-but-non-Map value (a {@link List}, {@link String}, or
     * scalar).
     *
     * <p>This closes a type-confusion redaction bypass: the per-field
     * sanitize ({@link #sanitizeHardware}) only runs for a Map, and the
     * Map-only ingest gate ({@code EndpointHardwareInventoryService.hasHardwareBlock})
     * also skips a non-Map — so a hardware list carrying a raw serial /
     * unnormalized MAC would otherwise reach the command-results sink
     * unsanitized with no 400. Mirrors {@code WinGetEgressPayloadPolicy.validate}
     * (~line 173).
     */
    private static Object requireMapOrAbsent(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?>) {
            return value;
        }
        throw new IllegalArgumentException(
                "hardware block at " + path + " must be an object, got "
                        + value.getClass().getSimpleName()
                        + " — a present-but-non-Map value would bypass the"
                        + " redaction boundary (absent / null is the valid opt-out).");
    }

    /**
     * Sanitize a hardware sub-tree map. Performs:
     * <ul>
     *   <li>Schema version validation</li>
     *   <li>Numeric range checks (cpu, ram, capacity, free)</li>
     *   <li>MAC normalization (lowercase canonical {@code aa:bb:..})</li>
     *   <li>Key-based strip / reject</li>
     *   <li>Value-pattern strip (paths, SIDs, machine GUIDs)</li>
     * </ul>
     */
    private Map<String, Object> sanitizeHardware(Map<String, Object> hardware, String path) {
        // Schema version check.
        Object schemaVersionObj = hardware.get("schemaVersion");
        if (schemaVersionObj != null) {
            Integer schemaVersion = toInt(schemaVersionObj, path + ".schemaVersion");
            if (schemaVersion == null || !ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
                throw new IllegalArgumentException(
                        "Unsupported hardware schema_version=" + schemaVersionObj
                                + " at " + path + ".schemaVersion");
            }
        }
        // Range checks on top-level scalars.
        requireNonNegative(hardware.get("cpuCores"), path + ".cpuCores");
        requireNonNegative(hardware.get("cpuFrequencyMhz"), path + ".cpuFrequencyMhz");
        requireNonNegative(hardware.get("ramTotalBytes"), path + ".ramTotalBytes");
        requireNonNegative(hardware.get("ramAvailableBytes"), path + ".ramAvailableBytes");

        // Disks + network interfaces must be arrays (or absent).
        ensureArrayOrAbsent(hardware.get("disks"), path + ".disks");
        ensureArrayOrAbsent(hardware.get("networkInterfaces"), path + ".networkInterfaces");

        // Per-disk range checks.
        Object disksRaw = hardware.get("disks");
        if (disksRaw instanceof List<?> disks) {
            int idx = 0;
            for (Object disk : disks) {
                if (disk instanceof Map<?, ?> diskMap) {
                    requireNonNegative(diskMap.get("capacityBytes"),
                            path + ".disks[" + idx + "].capacityBytes");
                    requireNonNegative(diskMap.get("freeBytes"),
                            path + ".disks[" + idx + "].freeBytes");
                }
                idx++;
            }
        }

        // Walk + sanitize recursively.
        return (Map<String, Object>) walkSanitize(hardware, path);
    }

    @SuppressWarnings("unchecked")
    private Object walkSanitize(Object node, String path) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String keyLower = key.toLowerCase(Locale.ROOT);
                String childPath = path + "." + key;

                if (REJECT_KEY_LOWER.contains(keyLower)) {
                    throw new IllegalArgumentException(
                            "Forbidden hardware-inventory key '"
                                    + key + "' at " + childPath
                                    + " — secrets must not appear in hardware payloads.");
                }
                if (STRIP_KEY_LOWER.contains(keyLower)) {
                    out.put(key, REDACTED);
                    continue;
                }
                // MAC normalization.
                if ("macaddress".equals(keyLower) || "mac".equals(keyLower)) {
                    Object value = entry.getValue();
                    if (value instanceof String s && !s.isEmpty()) {
                        out.put(key, normalizeMac(s, childPath));
                    } else {
                        out.put(key, value);
                    }
                    continue;
                }
                out.put(key, walkSanitize(entry.getValue(), childPath));
            }
            return out;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object element : iterable) {
                out.add(walkSanitize(element, path + "[" + (i++) + "]"));
            }
            return out;
        }
        if (node instanceof String s) {
            return redactStringValue(s, path);
        }
        return node;
    }

    /**
     * Apply value-level redaction to a string scalar.
     *
     * <p>Two-tier behavior (Codex 019e7007 post-impl iter-1 must-fix #3):
     * <ol>
     *   <li>Secret value patterns (Bearer / JWT / password=) fail-closed
     *       throw {@link IllegalArgumentException} — strip is unsafe
     *       because the surrounding string structure may still leak
     *       the secret length or position.</li>
     *   <li>Identifier patterns (user paths, SIDs, machine GUIDs) are
     *       replaced with {@value #REDACTED} — these have legitimate
     *       presence in error strings (path validation, OS reports)
     *       and reject would block all of those signals.</li>
     * </ol>
     */
    private String redactStringValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Tier 1 — fail-closed reject on secret value patterns.
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected at " + path
                                + " — tokens / passwords / bearer headers must not appear"
                                + " in hardware payloads.");
            }
        }
        // Tier 2 — strip identifier patterns.
        String out = value;
        out = USERS_PATH.matcher(out).replaceAll(REDACTED);
        out = UNIX_USER_PATH.matcher(out).replaceAll(REDACTED);
        out = WINDOWS_SID.matcher(out).replaceAll(REDACTED);
        out = MACHINE_GUID.matcher(out).replaceAll(REDACTED);
        return out;
    }

    /**
     * Normalize a MAC address to lowercase canonical
     * ({@code aa:bb:cc:dd:ee:ff}). Accepts colon or dash separators.
     */
    String normalizeMac(String raw, String path) {
        String stripped = raw.trim();
        if (!MAC_ANY.matcher(stripped).matches()) {
            throw new IllegalArgumentException(
                    "Invalid MAC address format at " + path + ": '" + raw + "'");
        }
        String canonical = stripped.toLowerCase(Locale.ROOT).replace('-', ':');
        if (!MAC_LOWERCASE_COLON.matcher(canonical).matches()) {
            throw new IllegalArgumentException(
                    "MAC normalization failed at " + path);
        }
        return canonical;
    }

    private static void requireNonNegative(Object value, String path) {
        if (value == null) {
            return;
        }
        Number n = toNumber(value, path);
        if (n != null && n.longValue() < 0) {
            throw new IllegalArgumentException(
                    "Negative value not allowed at " + path + ": " + value);
        }
    }

    private static void ensureArrayOrAbsent(Object value, String path) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + " but got " + value.getClass().getSimpleName());
        }
    }

    private static Integer toInt(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path + " but got '" + s + "'");
            }
        }
        throw new IllegalArgumentException(
                "Expected integer at " + path + " but got " + value.getClass().getSimpleName());
    }

    private static Number toNumber(Object value, String path) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ex2) {
                    throw new IllegalArgumentException(
                            "Expected number at " + path + " but got '" + s + "'");
                }
            }
        }
        throw new IllegalArgumentException(
                "Expected number at " + path + " but got " + value.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : in.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> m) {
                out.put(entry.getKey(), deepCopyMap((Map<String, Object>) m));
            } else if (value instanceof List<?> list) {
                out.put(entry.getKey(), deepCopyList(list));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepCopyList(List<?> in) {
        List<Object> out = new ArrayList<>(in.size());
        for (Object element : in) {
            if (element instanceof Map<?, ?> m) {
                out.add(deepCopyMap((Map<String, Object>) m));
            } else if (element instanceof List<?> nested) {
                out.add(deepCopyList(nested));
            } else {
                out.add(element);
            }
        }
        return out;
    }
}
