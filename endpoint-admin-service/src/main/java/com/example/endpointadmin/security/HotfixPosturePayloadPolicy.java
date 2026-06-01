package com.example.endpointadmin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * BE — pre-persist sanitizer/validator for the hotfix-posture sub-tree
 * of the agent {@code COLLECT_INVENTORY} payload (Faz 22.5, AG-037
 * ingest). Mirrors the AG-036 {@link OutdatedSoftwarePayloadPolicy}
 * + AG-033 {@code DeviceHealthPayloadPolicy} pattern, enforcing the
 * AG-037 contract redaction boundary (platform-agent
 * docs/COMMAND-CONTRACT.md §16, PR #45 merged 2026-06-01).
 *
 * <h3>Contract-freeze allowlist (Codex 019e81fe iter-2 P1.4 + iter-3)</h3>
 *
 * <p>Unlike the V20 outdated-software policy (forward-compat: unknown
 * top-level keys silently dropped), AG-037 is a strict allowlist:
 * <strong>any unknown top-level / nested key is fail-closed
 * REJECTED</strong>, NOT silently dropped. Wire shape additions require
 * an explicit contract bump.
 *
 * <h3>Wire path</h3>
 *
 * <p>The block is carried at {@code details.inventory.hotfixPosture}
 * (and the redundant top-level {@code details.hotfixPosture} accepted by
 * some agent versions). It is nullable — absent or explicit-{@code null}
 * when the caller did not opt in (the AG-025H lightweight default never
 * opts in). When <em>present</em> it MUST be the contract object: a
 * present-but-non-{@link Map} value is fail-closed REJECTED so a
 * type-confusion cannot bypass the redaction boundary.
 *
 * <h3>Redaction boundary (security invariant — DO NOT widen)</h3>
 *
 * <table>
 *   <tr><th>Field group</th><th>On the wire</th><th>NEVER on the wire</th></tr>
 *   <tr><td>Installed hotfix</td>
 *       <td>{@code kbId} ({@code ^KB[0-9]{4,10}$}),
 *           {@code installedOn} (nullable), {@code description}</td>
 *       <td>title, install client, install client app id, install account,
 *           command line, supersedence, product code, msi guid,
 *           deployment action</td></tr>
 *   <tr><td>Pending update</td>
 *       <td>{@code kbIds} (string array), {@code primaryCategory} (enum),
 *           {@code severity} (enum)</td>
 *       <td>title (operator-visible leak vector), description, vendor,
 *           installClientAppId, deploymentAction</td></tr>
 *   <tr><td>Agent health</td>
 *       <td>{@code wuaServiceState}, {@code bitsServiceState},
 *           {@code lastDetectAt}, {@code lastInstallAt},
 *           {@code autoUpdatePolicyEnabled},
 *           {@code autoUpdateEffectiveEnabled},
 *           {@code notificationLevel}</td>
 *       <td>raw registry blob, raw WUA error, last update KB id</td></tr>
 *   <tr><td>Errors</td>
 *       <td>{@code code} (enum), bounded {@code summary} (&le; 200,
 *           CRLF/tab stripped), {@code source}</td>
 *       <td>raw HRESULT, filesystem path, KB description, update title</td></tr>
 * </table>
 *
 * <h3>Count/cap invariants (Codex 019e81fe iter-3 P1.3)</h3>
 *
 * <ul>
 *   <li>{@code maxInstalled == 512} (const) + {@code maxPending == 20}
 *       (const) — fail-closed reject any other value.</li>
 *   <li>{@code installedHotfixes.size <= 512} +
 *       {@code pendingUpdates.size <= 20}.</li>
 *   <li>{@code installed_truncated=false} =>
 *       {@code installed_count == installed_children}.</li>
 *   <li>{@code installed_truncated=true} =>
 *       {@code installed_count >= installed_children
 *        AND installed_children <= 512}.</li>
 *   <li>Same two-branch rule for pending.</li>
 *   <li>{@code sum(pendingByCategory.count) == pendingTotalCount}.</li>
 *   <li>{@code pendingByCategory.category} unique per snapshot.</li>
 * </ul>
 *
 * <h3>notification_level (Codex 019e81fe iter-3 P1.4)</h3>
 *
 * <p>AUOptions registry value verbatim, bounded by regex
 * {@code ^[0-9]{1,4}$}. Empty string is normalized to {@code null}
 * BEFORE write. Missing / empty / present / malformed / out-of-bounds
 * branches all have explicit unit tests.
 */
@Component
public class HotfixPosturePayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(HotfixPosturePayloadPolicy.class);

    /** Bounded {@code probeError.summary} cap. */
    public static final int SUMMARY_MAX_LEN = 200;

    /** Agent-side installed-hotfix cap (wire contract const). */
    public static final int MAX_INSTALLED = 512;

    /** Agent-side pending-update cap (wire contract const). */
    public static final int MAX_PENDING = 20;

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "collectedAt",
            "probeDurationMs",
            "installedSourceUsed",
            "installedHotfixes",
            "installedCount",
            "installedTruncated",
            "pendingSourceUsed",
            "pendingUpdates",
            "pendingByCategory",
            "pendingTotalCount",
            "pendingTruncated",
            "healthSourceUsed",
            "agentHealth",
            "probeErrors"
    );

    /** Top-level required (Codex iter-3 freeze test alignment). */
    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "installedSourceUsed",
            "installedHotfixes",
            "installedCount",
            "installedTruncated",
            "pendingSourceUsed",
            "pendingUpdates",
            "pendingByCategory",
            "pendingTotalCount",
            "pendingTruncated",
            "healthSourceUsed",
            "agentHealth"
    );

    private static final Set<String> INSTALLED_ALLOWED_KEYS = Set.of(
            "kbId", "installedOn", "description"
    );

    private static final Set<String> PENDING_ALLOWED_KEYS = Set.of(
            "kbIds", "primaryCategory", "severity"
    );

    private static final Set<String> PENDING_CATEGORY_ALLOWED_KEYS = Set.of(
            "category", "count"
    );

    private static final Set<String> AGENT_HEALTH_ALLOWED_KEYS = Set.of(
            "wuaServiceState",
            "bitsServiceState",
            "lastDetectAt",
            "lastInstallAt",
            "autoUpdatePolicyEnabled",
            "autoUpdateEffectiveEnabled",
            "notificationLevel"
    );

    private static final Set<String> AGENT_HEALTH_REQUIRED_KEYS = Set.of(
            "wuaServiceState",
            "bitsServiceState"
    );

    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "source", "code", "summary"
    );

    /** Accepted {@code probeError.code} enum (wire contract §16.6). */
    private static final Set<String> PROBE_ERROR_CODES = Set.of(
            "UNSUPPORTED_PLATFORM",
            "ACCESS_DENIED",
            "COM_FAILED",
            "WSUS_UNREACHABLE",
            "POWERSHELL_MISSING",
            "POWERSHELL_TIMEOUT",
            "POWERSHELL_FAILED",
            "POWERSHELL_EMPTY_OUTPUT",
            "POWERSHELL_PARSE_ERROR",
            "REGISTRY_UNAVAILABLE",
            "SERVICE_QUERY_FAILED",
            "NO_EVIDENCE"
    );

    /** Accepted {@code installedSourceUsed} enum. */
    private static final Set<String> INSTALLED_SOURCES = Set.of("wua", "getHotfix", "none");

    /** Accepted {@code pendingSourceUsed} enum. */
    private static final Set<String> PENDING_SOURCES = Set.of("wua", "none");

    /** Accepted {@code healthSourceUsed} enum. */
    private static final Set<String> HEALTH_SOURCES = Set.of("service", "registry", "composite", "none");

    /** Accepted ServiceState enum (parity with wire). */
    private static final Set<String> SERVICE_STATES = Set.of("RUNNING", "STOPPED", "DISABLED", "UNKNOWN");

    /** Accepted primaryCategory enum. */
    private static final Set<String> CATEGORIES = Set.of(
            "SECURITY", "DEFINITION", "CRITICAL", "IMPORTANT", "DRIVER",
            "UPDATE_ROLLUP", "FEATURE_PACK", "SERVICE_PACK", "OPTIONAL",
            "TOOLS", "UNCATEGORIZED"
    );

    /** Accepted severity enum (MSRC ratings). */
    private static final Set<String> SEVERITIES = Set.of(
            "CRITICAL", "IMPORTANT", "MODERATE", "LOW", "UNSPECIFIED"
    );

    /**
     * Raw-subtree denylist (Codex iter-2 P1.4 + iter-3): any key whose
     * lowercase substring matches one of these MUST cause a fail-closed
     * reject BEFORE any allowlist projection runs. Secrets + forbidden
     * MS-update keys are reject-listed at the RAW tree so a key hidden
     * under an off-contract parent still aborts the submit.
     */
    private static final Set<String> REJECT_KEY_LOWER_SUBSTRINGS = Set.of(
            // Secret families
            "token", "bearer", "jwt", "password", "secret",
            "apikey", "accesstoken", "refreshtoken",
            // Forbidden MS-update fields (the redaction boundary)
            "productcode", "msiguid", "supersedence",
            "installclient", "installedby", "commandline",
            "accountname", "title", "clientapplicationid",
            "rawoutput", "deploymentaction",
            // PII / identifier leaks
            "sid", "username"
    );

    /** notification_level bounded numeric string (Codex iter-3 P1.4). */
    private static final Pattern NOTIFICATION_LEVEL = Pattern.compile("^[0-9]{1,4}$");

    /** KB identifier shape (Codex iter-3). */
    private static final Pattern KB_ID = Pattern.compile("^KB[0-9]{4,10}$");

    /** Bounded probe-summary path strip patterns (defense-in-depth). */
    private static final Pattern USERS_PATH = Pattern.compile(
            "(?i)c:\\\\users\\\\[^\\\\]+");
    private static final Pattern UNIX_USER_PATH = Pattern.compile(
            "/(home|Users)/[^/]+");

    private static final Pattern[] SECRET_VALUE_PATTERNS = new Pattern[]{
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._=+/-]{10,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\b(password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token)\\s*[=:]\\s*[^\\s]{4,}")
    };

    public static final String REDACTED = "<redacted>";

    /** Description max length (operator-friendly hotfix description). */
    private static final int DESCRIPTION_MAX_LEN = 512;

    /** Walk {@code details} producing a new map with the hotfix-posture
     *  sub-tree validated + projected onto the canonical v1 shape. The
     *  original map is not modified. */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Map<String, Object> sanitized = deepCopyMap(details);
        Object inventoryNode = sanitized.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) inventoryMap;
            Object hpNode = typed.get("hotfixPosture");
            if (hpNode instanceof Map<?, ?> hpMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> hpTyped = (Map<String, Object>) hpMap;
                typed.put("hotfixPosture",
                        sanitizeHotfixPosture(hpTyped, "$.inventory.hotfixPosture"));
            } else if (hpNode != null) {
                rejectNonMapBlock(hpNode, "$.inventory.hotfixPosture");
            }
        }
        Object topNode = sanitized.get("hotfixPosture");
        if (topNode instanceof Map<?, ?> topMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hpTyped = (Map<String, Object>) topMap;
            sanitized.put("hotfixPosture",
                    sanitizeHotfixPosture(hpTyped, "$.hotfixPosture"));
        } else if (topNode != null) {
            rejectNonMapBlock(topNode, "$.hotfixPosture");
        }
        return sanitized;
    }

    private static void rejectNonMapBlock(Object node, String path) {
        throw new IllegalArgumentException(
                "Expected object at " + path + " but got "
                        + node.getClass().getSimpleName()
                        + " — a present hotfix-posture block MUST be the contract object"
                        + " (a List / String / scalar would bypass the redaction boundary;"
                        + " absent or null is the valid opt-out).");
    }

    private Map<String, Object> sanitizeHotfixPosture(Map<String, Object> hp, String path) {
        // 0. Raw-subtree secret/forbidden key + value-pattern reject FIRST.
        rejectForbidden(hp, path);

        // 1. Strict allowlist projection at the top level (REJECT unknown,
        //    not silent drop — contract-freeze).
        for (Object keyObj : hp.keySet()) {
            String key = String.valueOf(keyObj);
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden hotfix-posture top-level key '" + key + "' at "
                                + path + "." + key
                                + " — contract-freeze allowlist (adding a field requires"
                                + " a contract bump).");
            }
        }

        // 2. Required top-level fields.
        for (String req : TOP_REQUIRED_KEYS) {
            if (!hp.containsKey(req) || hp.get(req) == null) {
                throw new IllegalArgumentException(
                        "Missing required hotfix-posture field '" + req + "' at "
                                + path + "." + req
                                + " — required v1 fields are fail-closed (no default).");
            }
        }

        // 3. schemaVersion const 1.
        long schemaVersion = toStrictLong(hp.get("schemaVersion"), path + ".schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains((int) schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported hotfix-posture schemaVersion=" + hp.get("schemaVersion")
                            + " at " + path + ".schemaVersion (expected " + ACCEPTED_SCHEMA_VERSIONS + ")");
        }

        // 4. Boolean fields.
        requireBoolean(hp.get("supported"), path + ".supported");
        requireBoolean(hp.get("probeComplete"), path + ".probeComplete");
        requireBoolean(hp.get("installedTruncated"), path + ".installedTruncated");
        requireBoolean(hp.get("pendingTruncated"), path + ".pendingTruncated");

        // 5. Source enums.
        String installedSrc = requireEnum(hp.get("installedSourceUsed"),
                path + ".installedSourceUsed", INSTALLED_SOURCES);
        String pendingSrc = requireEnum(hp.get("pendingSourceUsed"),
                path + ".pendingSourceUsed", PENDING_SOURCES);
        String healthSrc = requireEnum(hp.get("healthSourceUsed"),
                path + ".healthSourceUsed", HEALTH_SOURCES);

        // 6. Caps (Codex iter-3 P1.3): max_installed == 512 const,
        //    max_pending == 20 const. probeDurationMs optional but
        //    non-negative when present.
        long installedCount = toStrictLong(hp.get("installedCount"), path + ".installedCount");
        if (installedCount < 0) {
            throw new IllegalArgumentException(
                    "Negative installedCount at " + path + ".installedCount: " + installedCount);
        }
        long pendingTotalCount = toStrictLong(hp.get("pendingTotalCount"), path + ".pendingTotalCount");
        if (pendingTotalCount < 0) {
            throw new IllegalArgumentException(
                    "Negative pendingTotalCount at " + path + ".pendingTotalCount: "
                            + pendingTotalCount);
        }
        Integer probeDurationMs = null;
        if (hp.containsKey("probeDurationMs") && hp.get("probeDurationMs") != null) {
            long pd = toStrictLong(hp.get("probeDurationMs"), path + ".probeDurationMs");
            if (pd < 0 || pd > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "probeDurationMs out of range at " + path + ".probeDurationMs: " + pd);
            }
            probeDurationMs = (int) pd;
        }

        // 7. installedHotfixes — array, max 512, each row allowlist + KB regex.
        Object installedRaw = hp.get("installedHotfixes");
        if (!(installedRaw instanceof List<?> installedList)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + ".installedHotfixes but got "
                            + (installedRaw == null ? "null"
                                    : installedRaw.getClass().getSimpleName()));
        }
        if (installedList.size() > MAX_INSTALLED) {
            throw new IllegalArgumentException(
                    "Too many installedHotfixes at " + path + ".installedHotfixes: "
                            + installedList.size() + " exceeds " + MAX_INSTALLED);
        }
        List<Object> projectedInstalled = new ArrayList<>();
        for (int i = 0; i < installedList.size(); i++) {
            Object rowObj = installedList.get(i);
            String rowPath = path + ".installedHotfixes[" + i + "]";
            if (!(rowObj instanceof Map<?, ?> rowMap)) {
                throw new IllegalArgumentException(
                        "Expected object at " + rowPath + " but got "
                                + (rowObj == null ? "null" : rowObj.getClass().getSimpleName()));
            }
            projectedInstalled.add(projectInstalledHotfix(rowMap, rowPath));
        }

        // 8. pendingUpdates — array, max 20, each row allowlist + enum.
        Object pendingRaw = hp.get("pendingUpdates");
        if (!(pendingRaw instanceof List<?> pendingList)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + ".pendingUpdates but got "
                            + (pendingRaw == null ? "null"
                                    : pendingRaw.getClass().getSimpleName()));
        }
        if (pendingList.size() > MAX_PENDING) {
            throw new IllegalArgumentException(
                    "Too many pendingUpdates at " + path + ".pendingUpdates: "
                            + pendingList.size() + " exceeds " + MAX_PENDING);
        }
        List<Object> projectedPending = new ArrayList<>();
        for (int i = 0; i < pendingList.size(); i++) {
            Object rowObj = pendingList.get(i);
            String rowPath = path + ".pendingUpdates[" + i + "]";
            if (!(rowObj instanceof Map<?, ?> rowMap)) {
                throw new IllegalArgumentException(
                        "Expected object at " + rowPath + " but got "
                                + (rowObj == null ? "null" : rowObj.getClass().getSimpleName()));
            }
            projectedPending.add(projectPendingUpdate(rowMap, rowPath));
        }

        // 9. pendingByCategory — array, each row allowlist + unique
        //    category + sum invariant.
        Object byCategoryRaw = hp.get("pendingByCategory");
        if (!(byCategoryRaw instanceof List<?> byCategoryList)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + ".pendingByCategory but got "
                            + (byCategoryRaw == null ? "null"
                                    : byCategoryRaw.getClass().getSimpleName()));
        }
        List<Object> projectedByCategory = new ArrayList<>();
        Set<String> seenCategories = new java.util.HashSet<>();
        long sumByCategory = 0;
        for (int i = 0; i < byCategoryList.size(); i++) {
            Object rowObj = byCategoryList.get(i);
            String rowPath = path + ".pendingByCategory[" + i + "]";
            if (!(rowObj instanceof Map<?, ?> rowMap)) {
                throw new IllegalArgumentException(
                        "Expected object at " + rowPath + " but got "
                                + (rowObj == null ? "null" : rowObj.getClass().getSimpleName()));
            }
            Map<String, Object> projected = projectPendingByCategory(rowMap, rowPath);
            String category = (String) projected.get("category");
            if (!seenCategories.add(category)) {
                throw new IllegalArgumentException(
                        "Duplicate pendingByCategory.category at " + rowPath
                                + ": '" + category + "' appears multiple times");
            }
            sumByCategory += ((Number) projected.get("count")).longValue();
            projectedByCategory.add(projected);
        }
        if (sumByCategory != pendingTotalCount) {
            throw new IllegalArgumentException(
                    "pendingByCategory rollup mismatch at " + path
                            + ": sum(count)=" + sumByCategory
                            + " != pendingTotalCount=" + pendingTotalCount);
        }

        // 10. Count/cap invariants (Codex iter-3 P1.3).
        boolean installedTrunc = (Boolean) hp.get("installedTruncated");
        if (!installedTrunc && installedCount != projectedInstalled.size()) {
            throw new IllegalArgumentException(
                    "installedTruncated=false but installedCount=" + installedCount
                            + " != installedHotfixes.size=" + projectedInstalled.size()
                            + " at " + path);
        }
        if (installedTrunc && installedCount < projectedInstalled.size()) {
            throw new IllegalArgumentException(
                    "installedTruncated=true but installedCount=" + installedCount
                            + " < installedHotfixes.size=" + projectedInstalled.size()
                            + " (pre-truncation total must be >= persisted children) at " + path);
        }

        boolean pendingTrunc = (Boolean) hp.get("pendingTruncated");
        if (!pendingTrunc && pendingTotalCount != projectedPending.size()) {
            throw new IllegalArgumentException(
                    "pendingTruncated=false but pendingTotalCount=" + pendingTotalCount
                            + " != pendingUpdates.size=" + projectedPending.size()
                            + " at " + path);
        }
        if (pendingTrunc && pendingTotalCount < projectedPending.size()) {
            throw new IllegalArgumentException(
                    "pendingTruncated=true but pendingTotalCount=" + pendingTotalCount
                            + " < pendingUpdates.size=" + projectedPending.size()
                            + " at " + path);
        }

        // 11. agentHealth — strict allowlist + enums + nullable bools +
        //     notification_level normalize.
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedAgentHealth =
                projectAgentHealth((Map<String, Object>) hp.get("agentHealth"),
                        path + ".agentHealth");

        // 12. probeErrors — optional array, bounded summary.
        List<Object> projectedProbeErrors = null;
        if (hp.containsKey("probeErrors") && hp.get("probeErrors") != null) {
            Object probeErrorsRaw = hp.get("probeErrors");
            if (!(probeErrorsRaw instanceof List<?> errorsList)) {
                throw new IllegalArgumentException(
                        "Expected array at " + path + ".probeErrors but got "
                                + probeErrorsRaw.getClass().getSimpleName());
            }
            projectedProbeErrors = new ArrayList<>();
            for (int i = 0; i < errorsList.size(); i++) {
                Object errObj = errorsList.get(i);
                String errPath = path + ".probeErrors[" + i + "]";
                if (!(errObj instanceof Map<?, ?> errMap)) {
                    throw new IllegalArgumentException(
                            "Expected object at " + errPath + " but got "
                                    + (errObj == null ? "null" : errObj.getClass().getSimpleName()));
                }
                projectedProbeErrors.add(projectProbeError(errMap, errPath));
            }
        }

        // 13. Canonical-form projection — strict order so the canonical
        //     hash is deterministic regardless of agent map ordering.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", hp.get("schemaVersion"));
        out.put("supported", hp.get("supported"));
        out.put("probeComplete", hp.get("probeComplete"));
        // collectedAt and probeDurationMs are intentionally kept on the
        // sanitized output (controller payload includes them) but are
        // EXCLUDED from the canonical-form hash domain by the service
        // (Codex iter-4).
        if (hp.containsKey("collectedAt")) {
            out.put("collectedAt", hp.get("collectedAt"));
        }
        out.put("probeDurationMs", probeDurationMs);
        out.put("installedSourceUsed", installedSrc);
        out.put("installedHotfixes", projectedInstalled);
        out.put("installedCount", (int) installedCount);
        out.put("installedTruncated", installedTrunc);
        out.put("pendingSourceUsed", pendingSrc);
        out.put("pendingUpdates", projectedPending);
        out.put("pendingByCategory", projectedByCategory);
        out.put("pendingTotalCount", (int) pendingTotalCount);
        out.put("pendingTruncated", pendingTrunc);
        out.put("healthSourceUsed", healthSrc);
        out.put("agentHealth", projectedAgentHealth);
        if (projectedProbeErrors != null) {
            out.put("probeErrors", projectedProbeErrors);
        }
        return out;
    }

    private Map<String, Object> projectInstalledHotfix(Map<?, ?> row, String rowPath) {
        for (Object keyObj : row.keySet()) {
            String key = String.valueOf(keyObj);
            if (!INSTALLED_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden installedHotfix key '" + key + "' at " + rowPath + "." + key
                                + " — allowlist: " + INSTALLED_ALLOWED_KEYS);
            }
        }
        String kbId = requireString(row.get("kbId"), rowPath + ".kbId");
        if (!KB_ID.matcher(kbId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid kbId '" + kbId + "' at " + rowPath + ".kbId (expected ^KB[0-9]{4,10}$)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        // installedOn is nullable per wire contract (legacy Get-HotFix entries).
        out.put("installedOn", row.get("installedOn"));
        Object desc = row.get("description");
        if (desc != null) {
            String d = String.valueOf(desc);
            if (d.length() > DESCRIPTION_MAX_LEN) {
                d = d.substring(0, DESCRIPTION_MAX_LEN);
            }
            out.put("description", d);
        } else {
            out.put("description", null);
        }
        return out;
    }

    private Map<String, Object> projectPendingUpdate(Map<?, ?> row, String rowPath) {
        for (Object keyObj : row.keySet()) {
            String key = String.valueOf(keyObj);
            if (!PENDING_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden pendingUpdate key '" + key + "' at " + rowPath + "." + key
                                + " — allowlist: " + PENDING_ALLOWED_KEYS
                                + " (raw update title is intentionally NOT on the wire in v1).");
            }
        }
        // kbIds is required, []string (empty array allowed by wire).
        Object kbIdsRaw = row.get("kbIds");
        if (!(kbIdsRaw instanceof List<?> kbIdsList)) {
            throw new IllegalArgumentException(
                    "Expected array at " + rowPath + ".kbIds but got "
                            + (kbIdsRaw == null ? "null" : kbIdsRaw.getClass().getSimpleName()));
        }
        List<String> projectedKbIds = new ArrayList<>();
        for (int i = 0; i < kbIdsList.size(); i++) {
            Object kbObj = kbIdsList.get(i);
            String kbPath = rowPath + ".kbIds[" + i + "]";
            String kb = requireString(kbObj, kbPath);
            if (!KB_ID.matcher(kb).matches()) {
                throw new IllegalArgumentException(
                        "Invalid kbId '" + kb + "' at " + kbPath + " (expected ^KB[0-9]{4,10}$)");
            }
            projectedKbIds.add(kb);
        }
        String primaryCategory = requireEnum(row.get("primaryCategory"),
                rowPath + ".primaryCategory", CATEGORIES);
        String severity = requireEnum(row.get("severity"),
                rowPath + ".severity", SEVERITIES);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbIds", projectedKbIds);
        out.put("primaryCategory", primaryCategory);
        out.put("severity", severity);
        return out;
    }

    private Map<String, Object> projectPendingByCategory(Map<?, ?> row, String rowPath) {
        for (Object keyObj : row.keySet()) {
            String key = String.valueOf(keyObj);
            if (!PENDING_CATEGORY_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden pendingByCategory key '" + key + "' at " + rowPath + "." + key);
            }
        }
        String category = requireEnum(row.get("category"), rowPath + ".category", CATEGORIES);
        long count = toStrictLong(row.get("count"), rowPath + ".count");
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "pendingByCategory.count out of range at " + rowPath + ".count: " + count);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("count", (int) count);
        return out;
    }

    private Map<String, Object> projectAgentHealth(Map<String, Object> ah, String path) {
        if (ah == null) {
            throw new IllegalArgumentException(
                    "Missing agentHealth at " + path + " — required v1 field.");
        }
        for (Object keyObj : ah.keySet()) {
            String key = String.valueOf(keyObj);
            if (!AGENT_HEALTH_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden agentHealth key '" + key + "' at " + path + "." + key
                                + " — allowlist: " + AGENT_HEALTH_ALLOWED_KEYS);
            }
        }
        for (String req : AGENT_HEALTH_REQUIRED_KEYS) {
            if (!ah.containsKey(req) || ah.get(req) == null) {
                throw new IllegalArgumentException(
                        "Missing required agentHealth field '" + req + "' at "
                                + path + "." + req);
            }
        }
        String wua = requireEnum(ah.get("wuaServiceState"),
                path + ".wuaServiceState", SERVICE_STATES);
        String bits = requireEnum(ah.get("bitsServiceState"),
                path + ".bitsServiceState", SERVICE_STATES);

        // Nullable bool (3-state): TRUE / FALSE / NULL allowed; non-bool
        // non-null rejected.
        requireBooleanOrNull(ah.get("autoUpdatePolicyEnabled"),
                path + ".autoUpdatePolicyEnabled");
        requireBooleanOrNull(ah.get("autoUpdateEffectiveEnabled"),
                path + ".autoUpdateEffectiveEnabled");

        // notificationLevel (Codex iter-3 P1.4) — empty string normalize
        // to null; present must match regex; missing key OK (treated as
        // null).
        Object nlRaw = ah.get("notificationLevel");
        String notificationLevel = null;
        if (nlRaw != null) {
            if (!(nlRaw instanceof String nl)) {
                throw new IllegalArgumentException(
                        "Expected string at " + path + ".notificationLevel but got "
                                + nlRaw.getClass().getSimpleName());
            }
            if (nl.isEmpty()) {
                notificationLevel = null;
            } else {
                if (!NOTIFICATION_LEVEL.matcher(nl).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid notificationLevel '" + nl + "' at "
                                    + path + ".notificationLevel"
                                    + " (expected " + NOTIFICATION_LEVEL.pattern() + " or empty)");
                }
                notificationLevel = nl;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wuaServiceState", wua);
        out.put("bitsServiceState", bits);
        // Nullable fields preserved as null when missing or null (never
        // dropped — explicit null is part of the canonical hash domain).
        out.put("lastDetectAt", ah.get("lastDetectAt"));
        out.put("lastInstallAt", ah.get("lastInstallAt"));
        out.put("autoUpdatePolicyEnabled", ah.get("autoUpdatePolicyEnabled"));
        out.put("autoUpdateEffectiveEnabled", ah.get("autoUpdateEffectiveEnabled"));
        out.put("notificationLevel", notificationLevel);
        return out;
    }

    private Map<String, Object> projectProbeError(Map<?, ?> errMap, String errPath) {
        for (Object keyObj : errMap.keySet()) {
            String key = String.valueOf(keyObj);
            if (!PROBE_ERROR_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden probeError key '" + key + "' at " + errPath + "." + key
                                + " — allowlist: " + PROBE_ERROR_ALLOWED_KEYS);
            }
        }
        String code = requireEnum(errMap.get("code"), errPath + ".code", PROBE_ERROR_CODES);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        Object source = errMap.get("source");
        if (source != null) {
            // source can be any of the installed/pending/health source
            // attributions OR "none". Strict allowlist composed of all
            // four sets.
            String src = String.valueOf(source);
            if (!INSTALLED_SOURCES.contains(src)
                    && !HEALTH_SOURCES.contains(src)
                    && !PENDING_SOURCES.contains(src)) {
                throw new IllegalArgumentException(
                        "Unsupported probeError.source '" + src + "' at "
                                + errPath + ".source");
            }
            out.put("source", src);
        }
        Object summary = errMap.get("summary");
        if (summary != null) {
            if (!(summary instanceof String)) {
                throw new IllegalArgumentException(
                        "Expected string at " + errPath + ".summary but got "
                                + summary.getClass().getSimpleName());
            }
            String s = boundSummary((String) summary);
            out.put("summary", s);
        }
        return out;
    }

    /** Bound the probe-error summary: strip CRLF/tab + length cap +
     *  path redaction (defense-in-depth — agent should not embed paths,
     *  but neutralize rather than persist). */
    private String boundSummary(String input) {
        if (input == null) {
            return null;
        }
        // CRLF / tab strip (Codex iter-3 freeze tests).
        String s = input.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        // Path redaction (defense-in-depth).
        s = USERS_PATH.matcher(s).replaceAll(REDACTED);
        s = UNIX_USER_PATH.matcher(s).replaceAll(REDACTED);
        // Defense-in-depth: also reject secret value patterns (rejectForbidden
        // already ran but this method may be called from internal tests).
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(s).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected in probeError summary");
            }
        }
        // Length cap.
        if (s.length() > SUMMARY_MAX_LEN) {
            s = s.substring(0, SUMMARY_MAX_LEN);
        }
        return s;
    }

    /** Recursively walk the RAW sub-tree rejecting forbidden secret/MS-
     *  update keys AND secret value patterns BEFORE the allowlist
     *  projection runs. A forbidden key hidden under an off-contract
     *  parent still fail-closed the whole result. */
    private void rejectForbidden(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String keyLower = key.toLowerCase(Locale.ROOT);
                String childPath = path + "." + key;
                for (String forbiddenSub : REJECT_KEY_LOWER_SUBSTRINGS) {
                    if (keyLower.contains(forbiddenSub)) {
                        throw new IllegalArgumentException(
                                "Forbidden hotfix-posture key '" + key + "' at " + childPath
                                        + " — secrets / forbidden MS-update fields"
                                        + " (substring '" + forbiddenSub + "') must not appear"
                                        + " in hotfix-posture payloads.");
                    }
                }
                rejectForbidden(entry.getValue(), childPath);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                rejectForbidden(element, path + "[" + (i++) + "]");
            }
            return;
        }
        if (node instanceof String s) {
            assertNoSecretValue(s, path);
        }
    }

    private static void assertNoSecretValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected at " + path
                                + " — tokens / passwords / bearer headers must not appear"
                                + " in hotfix-posture payloads.");
            }
        }
    }

    private static void requireBoolean(Object value, String path) {
        if (value instanceof Boolean) return;
        throw new IllegalArgumentException(
                "Expected boolean at " + path + " but got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static void requireBooleanOrNull(Object value, String path) {
        if (value == null || value instanceof Boolean) return;
        throw new IllegalArgumentException(
                "Expected boolean or null at " + path + " but got "
                        + value.getClass().getSimpleName());
    }

    private static String requireString(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException("Missing required string at " + path);
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "Expected string at " + path + " but got " + value.getClass().getSimpleName());
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty string not allowed at " + path);
        }
        return s;
    }

    private static String requireEnum(Object value, String path, Set<String> allowed) {
        String s = requireString(value, path);
        if (!allowed.contains(s)) {
            throw new IllegalArgumentException(
                    "Unsupported value '" + s + "' at " + path + " (expected one of " + allowed + ")");
        }
        return s;
    }

    private static long toStrictLong(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException("Expected integer at " + path + " but got null");
        }
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l) return l;
        if (value instanceof Short s) return s.longValue();
        if (value instanceof Byte b) return b.longValue();
        if (value instanceof java.math.BigInteger bi) {
            if (bi.bitLength() >= 64) {
                throw new IllegalArgumentException(
                        "Integer at " + path + " exceeds 64-bit range: " + value);
            }
            return bi.longValue();
        }
        if (value instanceof java.math.BigDecimal bd) {
            try { return bd.longValueExact(); }
            catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path + " but got a non-integral decimal: " + value);
            }
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d != Math.rint(d) || Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path + " but got a non-integral decimal: " + value);
            }
            return (long) d;
        }
        throw new IllegalArgumentException(
                "Expected integer at " + path + " but got "
                        + value.getClass().getSimpleName() + ": " + value);
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

    /** Unused helper retained for symmetry with V20 policy; debug-log
     *  any keys not in the allowed set. */
    @SuppressWarnings("unused")
    private static void logDroppedKeys(Set<String> present, Set<String> allowed, String path) {
        if (!log.isDebugEnabled()) return;
        for (String key : present) {
            if (!allowed.contains(key)) {
                log.debug("Off-contract hotfix-posture key '{}' at {} (REJECTED — contract-freeze)",
                        key, path + "." + key);
            }
        }
    }

    /** Visible for tests: a single empty HashMap used as the canonical
     *  "agentHealth absent" sentinel. */
    @SuppressWarnings("unused")
    private static final Map<String, Object> EMPTY = new HashMap<>();
}
