package com.example.endpointadmin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — pre-persist sanitizer/validator for the outdated-software sub-tree
 * of the agent {@code COLLECT_INVENTORY} payload (Faz 22.5, AG-036 ingest).
 * Mirrors the AG-033 {@link DeviceHealthPayloadPolicy} pattern, enforcing
 * the outdated-software contract's redaction boundary
 * (schema/endpoint-outdated-software-payload-v1.schema.json, gitops PR #1145
 * commit {@code 73f0db0f}).
 *
 * <p>Wire path: the block is carried at
 * {@code details.inventory.outdatedSoftware} (and the redundant top-level
 * {@code details.outdatedSoftware} accepted by some agent versions). It is
 * nullable — absent or explicit-{@code null} when the caller did not opt in
 * (heartbeat / lightweight inventory default). When <em>present</em> it MUST
 * be the contract object: a present-but-non-{@link Map} value (a
 * {@code List} / {@code String} / scalar) is fail-closed <em>rejected</em>
 * (Codex 019e7693 RED). Otherwise the type-confusion would skip
 * {@link #sanitizeOutdatedSoftware} entirely — the per-package
 * {@code additionalProperties:false} redaction boundary never runs — and the
 * raw block (publisher / downloadUrl / display name PII) would pass through
 * into the persisted {@code result_payload.details} unsanitized (the ingest
 * gate also accepts only a Map, so it skips ingest and the per-package reject
 * too). A present-but-malformed redaction-gated block must FAIL the submit.
 *
 * <p><strong>Allowlist projection.</strong> The output of
 * {@link #sanitize(Map)} is the SAME object that flows into BOTH the
 * persisted command-result {@code result_payload.details} and the
 * outdated-software snapshot {@code redacted_payload}. The policy therefore
 * does not merely walk-and-copy the agent map (which would carry
 * off-contract keys verbatim into both sinks). It builds a <em>canonical
 * projection</em> containing only the v1 contract keys; any unknown
 * top-level key is DROPPED (and debug-logged). This is exactly the
 * contract's forward-compat rule
 * (docs/faz-22-outdated-software-contract-v1.md §"Forward-compat rule":
 * unknown top-level fields are <em>ignored, not persisted</em>). The
 * stricter per-package facet ({@code additionalProperties: false}) is
 * fail-closed <em>rejected</em> (not dropped) on any extra key, because a
 * display name / publisher / install location / license / download URL has
 * no legitimate place there — that is the redaction boundary.
 *
 * <p><strong>Fail-closed required fields.</strong> The policy validates
 * required v1 fields like a JSON-schema validator: a missing/wrong-typed
 * required top-level field, a missing/wrong-typed required package
 * subfield, or a non-enum {@code sourceUsed} is fail-closed <em>rejected</em>
 * with {@link IllegalArgumentException}. A minimal
 * {@code {"schemaVersion":1}} block is rejected — the downstream service
 * must NEVER synthesize an "up to date" default for required fields.
 *
 * <p>Redaction boundary (security invariant — DO NOT widen):
 * <table>
 *   <tr><th>Field group</th><th>On the wire</th><th>NEVER on the wire</th></tr>
 *   <tr><td>Package</td><td>{@code packageId} ({@code ^\S+$}),
 *       {@code installedVersion}, {@code availableVersion}</td>
 *       <td>display name, publisher, install location, license,
 *       download URL</td></tr>
 *   <tr><td>Errors</td><td>{@code code} (enum), bounded {@code summary}
 *       (&le;200, static phrasing)</td><td>raw errno, filesystem path,
 *       package display name</td></tr>
 *   <tr><td>Caps</td><td>{@code maxUpgrade} (agent const 512)</td>
 *       <td>not payload-configurable</td></tr>
 * </table>
 */
@Component
public class OutdatedSoftwarePayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutdatedSoftwarePayloadPolicy.class);

    /**
     * Bounded probeError {@code summary} cap. The contract pins
     * {@code maxLength: 200}; the policy enforces it on the projection so
     * the command-result payload, the snapshot {@code redacted_payload},
     * and any audit copy are all bounded — not just the persisted entity
     * scalar. Exposed so the snapshot service shares the SAME constant.
     */
    public static final int SUMMARY_MAX_LEN = 200;

    /**
     * Keys whose value, anywhere in the outdated-software sub-tree, must
     * cause a fail-closed reject. Secrets have no legitimate
     * outdated-software presence.
     */
    private static final Set<String> REJECT_KEY_LOWER = Set.of(
            "token",
            "bearer",
            "jwt",
            "password",
            "secret"
    );

    /**
     * Allowlist of v1 top-level outdated-software keys (contract
     * {@code additionalProperties: false} + required set, plus the optional
     * {@code probeErrors}). Any other top-level key is DROPPED (forward-compat
     * "ignore unknown", never persisted).
     */
    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "upgradeCount",
            "upgrade",
            "upgradeTruncated",
            "maxUpgrade",
            "sourceUsed",
            "probeErrors",
            "probeDurationMs"
    );

    /** Required top-level fields (contract {@code required}). */
    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "upgradeCount",
            "upgrade",
            "upgradeTruncated",
            "maxUpgrade",
            "sourceUsed",
            "probeDurationMs"
    );

    /**
     * Allowlist of keys on an {@code upgrade[]} package facet. The contract's
     * {@code outdatedPackage} is {@code additionalProperties: false} with
     * EXACTLY these three required keys. The match is <strong>exact and
     * case-sensitive</strong>: any other key (name, publisher, path, license,
     * url, ...) <em>and any case-variant</em> ({@code PackageId},
     * {@code Publisher}, {@code InstalledVersion}, ...) is fail-closed
     * rejected. A case-insensitive allowlist would silently DROP a
     * case-variant extra instead of rejecting it as the
     * {@code additionalProperties:false} violation it is — that is precisely
     * the redaction boundary the agent enforces at source, mirrored here
     * machine-side.
     */
    private static final Set<String> PACKAGE_ALLOWED_KEYS_EXACT = Set.of(
            "packageId",
            "installedVersion",
            "availableVersion"
    );

    /**
     * Lowercased forms of the three exact package keys. A package key that
     * is NOT an exact contract key but whose lowercase form collides with one
     * (e.g. {@code PackageId}, {@code PACKAGEID}) is a case-variant of a
     * redaction-gated field — rejected with a targeted "case-variant" message
     * so the operator sees it is the additionalProperties violation, not a
     * stray off-contract field. Any other off-contract key (e.g.
     * {@code publisher}, {@code downloadUrl}) is rejected with the generic
     * forbidden-key message.
     */
    private static final Set<String> PACKAGE_ALLOWED_KEYS_LOWER = Set.of(
            "packageid",
            "installedversion",
            "availableversion"
    );

    /** Allowlist of {@code probeErrors[]} element keys (contract
     *  {@code probeError}; {@code code} required, {@code source}/{@code summary}
     *  optional). */
    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "source",
            "code",
            "summary"
    );

    /** Accepted {@code probeError.code} enum (contract). */
    private static final Set<String> PROBE_ERROR_CODES = Set.of(
            "UNSUPPORTED_PLATFORM",
            "WINGET_NOT_FOUND",
            "WINGET_TIMEOUT",
            "WINGET_FAILED",
            "WINGET_EMPTY_OUTPUT",
            "WINGET_PARSE_ERROR"
    );

    /** packageId shape — no whitespace (it is an id, not a display name). */
    private static final Pattern PACKAGE_ID = Pattern.compile("^\\S+$");

    /** Accepted {@code schemaVersion} values (current agent emits 1). */
    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    /**
     * Agent-side upgrade cap. The contract pins {@code maxUpgrade} to
     * {@code const: 512} and the {@code upgrade} array to {@code maxItems:
     * 512}. Both are fail-closed enforced here so an off-contract-but-
     * otherwise-valid payload (e.g. {@code maxUpgrade=1024} or a
     * 600-element upgrade array) cannot slip past the policy into the
     * command-result payload + snapshot {@code redacted_payload}.
     */
    public static final int MAX_UPGRADE = 512;

    /** Contract {@code packageId} maxLength. */
    private static final int PACKAGE_ID_MAX_LEN = 256;

    /** Contract {@code installedVersion} / {@code availableVersion}
     *  maxLength. */
    private static final int VERSION_MAX_LEN = 128;

    /**
     * Upper bound for fields persisted in an {@code INTEGER} column
     * (V20: {@code upgrade_count}, {@code max_upgrade},
     * {@code probe_duration_ms}). Matches PostgreSQL {@code INT} so the
     * policy fail-closed rejects a value that would overflow the column at
     * persist time instead of surfacing a later DB error after the snapshot
     * row half-commits.
     */
    private static final long INT_MAX = Integer.MAX_VALUE;

    /** Accepted {@code sourceUsed} enum values. */
    private static final Set<String> ACCEPTED_SOURCE_USED =
            Set.of("winget", "none");

    /** Value pattern matchers — applied to every string scalar, regardless
     * of key name (defense-in-depth on probeError summaries, which the
     * contract says must never be a path / errno). */
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
     * Value-level secret pattern denylist (parity with the hardware/device-
     * health policy). Key-level reject covers
     * {@code token / password / jwt / bearer / secret}, but raw
     * agent-reported strings (e.g. a {@code probeError.summary}) can also
     * leak secrets out of band. These patterns fail-closed reject the entire
     * result so the snapshot, the command result, and any audit copy of the
     * payload all roll back together.
     */
    private static final Pattern[] SECRET_VALUE_PATTERNS = new Pattern[]{
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._=+/-]{10,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\b(password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token)\\s*[=:]\\s*[^\\s]{4,}")
    };

    public static final String REDACTED = "<redacted>";

    /**
     * Walk {@code details} producing a new map with the outdated-software
     * sub-tree validated + projected onto the canonical v1 shape. The
     * original map is not modified.
     *
     * @param details the agent {@code result_payload.details} map
     * @return sanitized {@code effectiveDetails} — caller hands this to
     *         the persisted {@code result_payload} and to the
     *         outdated-software ingest hook. {@code null} when {@code details}
     *         is {@code null}.
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
            Object osNode = typed.get("outdatedSoftware");
            if (osNode instanceof Map<?, ?> osMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> osTyped = (Map<String, Object>) osMap;
                typed.put("outdatedSoftware",
                        sanitizeOutdatedSoftware(osTyped, "$.inventory.outdatedSoftware"));
            } else if (osNode != null) {
                // Present-but-wrong-type (a List/String/scalar — NOT the
                // contract object) is fail-closed REJECTED, never passed
                // through. Otherwise a `outdatedSoftware: [ {publisher,
                // downloadUrl, ...} ]` list would skip sanitizeOutdated-
                // Software entirely (the redaction boundary never runs) and
                // the raw PII-bearing block would persist into
                // result_payload.details. Absent (osNode == null because the
                // key is missing) OR explicit-null is the legitimate optional
                // opt-out and is left untouched.
                rejectNonMapBlock(osNode, "$.inventory.outdatedSoftware");
            }
        }
        // Some agent versions may also place the block at the top level —
        // sanitize that too if present (parity with device-health policy).
        Object topNode = sanitized.get("outdatedSoftware");
        if (topNode instanceof Map<?, ?> topMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> osTyped = (Map<String, Object>) topMap;
            sanitized.put("outdatedSoftware",
                    sanitizeOutdatedSoftware(osTyped, "$.outdatedSoftware"));
        } else if (topNode != null) {
            // Same fail-closed reject for the top-level alias (present-but-
            // wrong-type only; absent/explicit-null is the valid opt-out).
            rejectNonMapBlock(topNode, "$.outdatedSoftware");
        }
        return sanitized;
    }

    /**
     * Fail-closed reject a present-but-non-{@link Map} outdated-software
     * block. A {@code List} / {@code String} / scalar at this path is NOT the
     * contract object, so the per-package {@code additionalProperties:false}
     * redaction boundary in {@link #sanitizeOutdatedSoftware} would never run
     * on it — the raw block would otherwise flow unsanitized into the
     * persisted {@code result_payload.details} (the
     * {@code hasOutdatedSoftwareBlock} ingest gate also accepts only a Map, so
     * it would skip ingest and never hit the per-package reject either). A
     * present-but-malformed redaction-gated block must FAIL the submit, never
     * pass through. The call site only invokes this for a present, non-null,
     * wrong-typed value; an absent key or an explicit {@code null} is the
     * legitimate optional opt-out and is left untouched.
     */
    private static void rejectNonMapBlock(Object node, String path) {
        throw new IllegalArgumentException(
                "Expected object at " + path + " but got "
                        + node.getClass().getSimpleName()
                        + " — a present outdated-software block MUST be the contract"
                        + " object (a List / String / scalar would bypass the"
                        + " per-package redaction boundary; absent or null is the"
                        + " valid opt-out).");
    }

    /**
     * Validate + project an outdated-software sub-tree map onto the canonical
     * v1 shape. Performs (in order):
     * <ul>
     *   <li>Secret key/value reject over the RAW sub-tree (so a secret in
     *       an off-contract key is rejected before that key is dropped)</li>
     *   <li>Required top-level field presence + type (fail-closed)</li>
     *   <li>Schema version ({@code const 1}) + {@code sourceUsed} enum</li>
     *   <li>Numeric range checks (counts, durations, the maxUpgrade const)</li>
     *   <li>{@code upgrade} array shape + maxItems + per-package allowlist
     *       (reject) + {@code packageId} pattern + version bounds</li>
     *   <li>{@code probeErrors[]} shape ({@code code} enum, bounded summary)</li>
     *   <li>Allowlist projection — only canonical v1 keys survive; unknown
     *       top-level keys are dropped + debug-logged</li>
     * </ul>
     */
    private Map<String, Object> sanitizeOutdatedSoftware(Map<String, Object> os, String path) {
        // 0. Secret reject over the RAW sub-tree FIRST. A secret hidden in
        //    an off-contract key (which the projection below would drop)
        //    must still fail-closed the whole result, not be silently
        //    discarded. Also applies value-pattern reject to every scalar.
        rejectSecrets(os, path);

        // 1. Required top-level fields (fail-closed). A minimal
        //    {"schemaVersion":1} block is rejected here.
        for (String req : TOP_REQUIRED_KEYS) {
            if (!os.containsKey(req) || os.get(req) == null) {
                throw new IllegalArgumentException(
                        "Missing required outdated-software field '" + req + "' at "
                                + path + "." + req
                                + " — required v1 fields are fail-closed (no up-to-date default).");
            }
        }

        // 2. Schema version (const 1). Strict integer typing — a string
        //    "1" is NOT silently coerced (contract: type integer, const 1).
        long schemaVersion = toStrictLong(os.get("schemaVersion"), path + ".schemaVersion");
        if (schemaVersion < Integer.MIN_VALUE || schemaVersion > Integer.MAX_VALUE
                || !ACCEPTED_SCHEMA_VERSIONS.contains((int) schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported outdated-software schema_version=" + os.get("schemaVersion")
                            + " at " + path + ".schemaVersion");
        }

        // 3. Boolean-typed required fields.
        requireBoolean(os.get("supported"), path + ".supported");
        requireBoolean(os.get("probeComplete"), path + ".probeComplete");
        requireBoolean(os.get("upgradeTruncated"), path + ".upgradeTruncated");

        // 4. sourceUsed enum (required + must be winget|none).
        String su = String.valueOf(os.get("sourceUsed"));
        if (!ACCEPTED_SOURCE_USED.contains(su)) {
            throw new IllegalArgumentException(
                    "Unsupported outdated-software sourceUsed='" + su
                            + "' at " + path + ".sourceUsed (expected winget | none)");
        }

        // 5. maxUpgrade is const 512 (contract). Validate FIRST so the
        //    upgradeCount upper bound and the upgrade[] cap below both key
        //    off the proven const.
        requireIntegerConst(os.get("maxUpgrade"), path + ".maxUpgrade", MAX_UPGRADE);

        // 6. Top-level numeric ranges. Strict integer typing (Integer/Long
        //    only, no string coercion, no non-integral decimal) + the column
        //    bound. upgradeCount must be in [0, maxUpgrade]; probeDurationMs
        //    in [0, INT_MAX].
        requireIntegerInRange(os.get("upgradeCount"), path + ".upgradeCount", 0, MAX_UPGRADE);
        requireIntegerInRange(os.get("probeDurationMs"), path + ".probeDurationMs", 0, INT_MAX);

        // 7. upgrade must be an array, capped at maxItems (contract: 512),
        //    and each entry must conform to the strict package allowlist +
        //    packageId pattern + version length bounds.
        Object upgradeRaw = os.get("upgrade");
        if (!(upgradeRaw instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + ".upgrade but got "
                            + upgradeRaw.getClass().getSimpleName());
        }
        if (((List<?>) upgradeRaw).size() > MAX_UPGRADE) {
            throw new IllegalArgumentException(
                    "Too many upgrade entries at " + path + ".upgrade: "
                            + ((List<?>) upgradeRaw).size()
                            + " exceeds the contract cap of " + MAX_UPGRADE
                            + " (maxItems).");
        }
        List<Object> projectedPackages = new ArrayList<>();
        int idx = 0;
        for (Object pkgObj : (List<?>) upgradeRaw) {
            String pkgPath = path + ".upgrade[" + idx + "]";
            if (!(pkgObj instanceof Map<?, ?> pkgMap)) {
                throw new IllegalArgumentException(
                        "Expected object at " + pkgPath + " but got "
                                + (pkgObj == null ? "null"
                                        : pkgObj.getClass().getSimpleName()));
            }
            projectedPackages.add(projectPackage(pkgMap, pkgPath));
            idx++;
        }

        // 8. probeErrors — optional array of bounded {source,code,summary}.
        Object probeErrorsRaw = os.get("probeErrors");
        List<Object> projectedProbeErrors = null;
        if (probeErrorsRaw != null) {
            if (!(probeErrorsRaw instanceof List<?>)) {
                throw new IllegalArgumentException(
                        "Expected array at " + path + ".probeErrors but got "
                                + probeErrorsRaw.getClass().getSimpleName());
            }
            projectedProbeErrors = new ArrayList<>();
            int ei = 0;
            for (Object errObj : (List<?>) probeErrorsRaw) {
                String errPath = path + ".probeErrors[" + ei + "]";
                if (!(errObj instanceof Map<?, ?> errMap)) {
                    throw new IllegalArgumentException(
                            "Expected object at " + errPath + " but got "
                                    + (errObj == null ? "null"
                                            : errObj.getClass().getSimpleName()));
                }
                projectedProbeErrors.add(projectProbeError(errMap, errPath));
                ei++;
            }
        }

        // 9. Allowlist projection — assemble the canonical v1 map. Only
        //    contract keys survive; unknown top-level keys are dropped
        //    (debug-logged), never persisted. The `upgrade` array ALWAYS
        //    serializes as [] (never null) per the contract.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", os.get("schemaVersion"));
        out.put("supported", os.get("supported"));
        out.put("probeComplete", os.get("probeComplete"));
        out.put("upgradeCount", os.get("upgradeCount"));
        out.put("upgrade", projectedPackages);
        out.put("upgradeTruncated", os.get("upgradeTruncated"));
        out.put("maxUpgrade", os.get("maxUpgrade"));
        out.put("sourceUsed", su);
        if (projectedProbeErrors != null) {
            out.put("probeErrors", projectedProbeErrors);
        }
        out.put("probeDurationMs", os.get("probeDurationMs"));

        logDroppedKeys(os.keySet(), TOP_ALLOWED_KEYS, path);
        return out;
    }

    /**
     * Validate + project a single {@code upgrade[]} package facet. The
     * package facet is the redaction boundary: any key outside the three-key
     * allowlist is fail-closed REJECTED (not dropped) — a display name /
     * publisher / install location / license / download URL has no
     * legitimate place on the wire. The three contract keys are all required
     * and length-bounded; {@code packageId} must match {@code ^\S+$}.
     */
    private Map<String, Object> projectPackage(Map<?, ?> pkgMap, String pkgPath) {
        for (Map.Entry<?, ?> entry : pkgMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (PACKAGE_ALLOWED_KEYS_EXACT.contains(key)) {
                continue;
            }
            // Exact case-sensitive additionalProperties:false: any key not
            // EXACTLY one of the three contract keys is rejected. A
            // case-variant whose lowercase form collides with a contract key
            // (e.g. PackageId / INSTALLEDVERSION) is the additionalProperties
            // violation, NOT a field to silently drop — distinguish it for a
            // clearer operator error.
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (PACKAGE_ALLOWED_KEYS_LOWER.contains(keyLower)) {
                throw new IllegalArgumentException(
                        "Forbidden outdated-software package key '" + key + "' at "
                                + pkgPath + "." + key
                                + " — case-variant of a contract key; the package facet"
                                + " redaction boundary requires the EXACT keys"
                                + " {packageId, installedVersion, availableVersion}"
                                + " (additionalProperties:false, case-sensitive).");
            }
            throw new IllegalArgumentException(
                    "Forbidden outdated-software package key '" + key + "' at "
                            + pkgPath + "." + key
                            + " — the package facet redaction boundary allows ONLY"
                            + " {packageId, installedVersion, availableVersion} (no"
                            + " display name / publisher / install location / license"
                            + " / download URL).");
        }
        String packageId = requireBoundedString(pkgMap.get("packageId"),
                pkgPath + ".packageId", PACKAGE_ID_MAX_LEN);
        if (!PACKAGE_ID.matcher(packageId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid packageId '" + packageId + "' at " + pkgPath
                            + ".packageId (expected ^\\S+$ — an id, not a display name)");
        }
        String installedVersion = requireBoundedString(pkgMap.get("installedVersion"),
                pkgPath + ".installedVersion", VERSION_MAX_LEN);
        String availableVersion = requireBoundedString(pkgMap.get("availableVersion"),
                pkgPath + ".availableVersion", VERSION_MAX_LEN);

        // Project the canonical three-key package facet (all three required +
        // already validated). The facet has no extra keys (rejected above),
        // so this is a straight canonical re-emit.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packageId", packageId);
        out.put("installedVersion", installedVersion);
        out.put("availableVersion", availableVersion);
        return out;
    }

    /**
     * Validate + project a single {@code probeErrors[]} element. {@code code}
     * is required + enum-checked; {@code source} (if present) must be the
     * {@code winget|none} enum; {@code summary} (if present) is bounded to
     * {@link #SUMMARY_MAX_LEN} HERE (so the command-result payload is also
     * bounded, not just the snapshot scalar) after value-redaction. Unknown
     * keys (e.g. {@code rawOutput}, {@code stackTrace}) are dropped +
     * debug-logged.
     */
    private Map<String, Object> projectProbeError(Map<?, ?> errMap, String errPath) {
        Object code = errMap.get("code");
        if (code == null) {
            throw new IllegalArgumentException(
                    "Missing required probeError code at " + errPath + ".code");
        }
        String codeStr = String.valueOf(code);
        if (!PROBE_ERROR_CODES.contains(codeStr)) {
            throw new IllegalArgumentException(
                    "Unsupported probeError code '" + codeStr + "' at "
                            + errPath + ".code");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object source = errMap.get("source");
        if (source != null) {
            String src = String.valueOf(source);
            if (!ACCEPTED_SOURCE_USED.contains(src)) {
                throw new IllegalArgumentException(
                        "Unsupported probeError source '" + src + "' at "
                                + errPath + ".source (expected winget | none)");
            }
            out.put("source", src);
        }
        out.put("code", codeStr);
        Object summary = errMap.get("summary");
        if (summary != null) {
            // Contract: summary is type:string. Fail-closed reject a
            // non-string (a number/object/array would otherwise be coerced
            // by String.valueOf into bogus operator text). Then re-apply the
            // identifier strip on the projected scalar (value-redaction
            // already ran in rejectSecrets) and bound the length.
            if (!(summary instanceof String)) {
                throw new IllegalArgumentException(
                        "Expected string at " + errPath + ".summary but got "
                                + summary.getClass().getSimpleName());
            }
            String s = redactStringValue((String) summary, errPath + ".summary");
            if (s.length() > SUMMARY_MAX_LEN) {
                s = s.substring(0, SUMMARY_MAX_LEN);
            }
            out.put("summary", s);
        }
        logDroppedKeys(stringKeys(errMap), PROBE_ERROR_ALLOWED_KEYS, errPath);
        return out;
    }

    /**
     * Recursively walk the RAW sub-tree rejecting forbidden secret keys
     * and secret value patterns BEFORE the allowlist projection drops any
     * off-contract key. A secret in a soon-to-be-dropped key must still
     * fail-closed the entire result.
     */
    private void rejectSecrets(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String keyLower = key.toLowerCase(Locale.ROOT);
                String childPath = path + "." + key;
                if (REJECT_KEY_LOWER.contains(keyLower)) {
                    throw new IllegalArgumentException(
                            "Forbidden outdated-software key '" + key + "' at "
                                    + childPath
                                    + " — secrets must not appear in outdated-software payloads.");
                }
                rejectSecrets(entry.getValue(), childPath);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                rejectSecrets(element, path + "[" + (i++) + "]");
            }
            return;
        }
        if (node instanceof String s) {
            assertNoSecretValue(s, path);
        }
    }

    /** Fail-closed reject a string scalar carrying a secret value pattern
     *  (Bearer / JWT / kv leak). */
    private static void assertNoSecretValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected at " + path
                                + " — tokens / passwords / bearer headers must not appear"
                                + " in outdated-software payloads.");
            }
        }
    }

    /**
     * Apply identifier-level redaction to a string scalar (user paths,
     * SIDs, machine GUIDs replaced with {@value #REDACTED}). Secret value
     * patterns were already fail-closed rejected by {@link #rejectSecrets}.
     * The contract says a probeError summary must never be a path; if an
     * off-contract agent embeds one, we neutralize it rather than persist.
     */
    private String redactStringValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Defense-in-depth: also reject here (a value reaching this method
        // outside the rejectSecrets walk still must not leak a secret).
        assertNoSecretValue(value, path);
        String out = value;
        out = USERS_PATH.matcher(out).replaceAll(REDACTED);
        out = UNIX_USER_PATH.matcher(out).replaceAll(REDACTED);
        out = WINDOWS_SID.matcher(out).replaceAll(REDACTED);
        out = MACHINE_GUID.matcher(out).replaceAll(REDACTED);
        return out;
    }

    private static void requireBoolean(Object value, String path) {
        if (value instanceof Boolean) {
            return;
        }
        throw new IllegalArgumentException(
                "Expected boolean at " + path + " but got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /**
     * Require a non-blank string within the contract length bound. The
     * contract types the three package fields as {@code string, minLength:1}
     * (packageId/version) with a {@code maxLength}. Fail-closed rejects a
     * null, a non-string, a blank, or an over-length value.
     */
    private static String requireBoundedString(Object value, String path, int maxLen) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required string at " + path);
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "Expected string at " + path + " but got "
                            + value.getClass().getSimpleName());
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty string not allowed at " + path + " (contract minLength 1)");
        }
        if (s.length() > maxLen) {
            throw new IllegalArgumentException(
                    "String exceeds contract maxLength (" + maxLen + ") at " + path
                            + ": length " + s.length());
        }
        return s;
    }

    /**
     * Strict required-integer-in-range check. Accepts ONLY a JSON integer
     * decoded as {@link Integer} or {@link Long} (Jackson's integral
     * mapping). Fail-closed rejects:
     * <ul>
     *   <li>{@code null} (caller already proved presence, but be defensive)</li>
     *   <li>a {@code String} (e.g. {@code "1"}) — no silent string coercion</li>
     *   <li>a non-integral {@code Double}/{@code Float}/{@code BigDecimal}
     *       (e.g. {@code 1.5}) — a fractional value has no place in an
     *       integer column</li>
     * </ul>
     * and enforces {@code [min, max]} (max is the persisting column's
     * ceiling — {@code INT_MAX} for an {@code INTEGER} column, or
     * {@code MAX_UPGRADE} for the upgrade count).
     */
    private static void requireIntegerInRange(Object value, String path, long min, long max) {
        long v = toStrictLong(value, path);
        if (v < min) {
            throw new IllegalArgumentException(
                    (min == 0 ? "Negative value not allowed at "
                              : "Value below minimum (" + min + ") at ")
                            + path + ": " + value);
        }
        if (v > max) {
            throw new IllegalArgumentException(
                    "Value exceeds bound (" + max + ") at " + path + ": " + value);
        }
    }

    /** Strict required-integer with an exact expected value (contract
     *  {@code const}). Same strict typing as {@link #requireIntegerInRange}. */
    private static void requireIntegerConst(Object value, String path, long expected) {
        long v = toStrictLong(value, path);
        if (v != expected) {
            throw new IllegalArgumentException(
                    "Value at " + path + " must equal " + expected
                            + " (contract const) but was " + value);
        }
    }

    /**
     * Decode a value to a {@code long} accepting ONLY a true JSON integer
     * (Integer/Long; integral Short/Byte are also accepted as they are
     * non-fractional). A {@code String}, or a {@code Double}/{@code Float}/
     * {@code BigDecimal} carrying a fractional part, is fail-closed
     * rejected. A {@code BigInteger} outside {@code long} range is rejected
     * (it could not fit the column either). This is the single coercion gate
     * that keeps the numeric parser strict.
     */
    private static long toStrictLong(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Expected integer at " + path + " but got null");
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Short s) {
            return s.longValue();
        }
        if (value instanceof Byte b) {
            return b.longValue();
        }
        if (value instanceof java.math.BigInteger bi) {
            if (bi.bitLength() >= 64) {
                throw new IllegalArgumentException(
                        "Integer at " + path + " exceeds 64-bit range: " + value);
            }
            return bi.longValue();
        }
        if (value instanceof java.math.BigDecimal bd) {
            try {
                return bd.longValueExact();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path
                                + " but got a non-integral / out-of-range decimal: " + value);
            }
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d != Math.rint(d) || Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path
                                + " but got a non-integral decimal: " + value);
            }
            if (d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Integer at " + path + " exceeds 64-bit range: " + value);
            }
            return (long) d;
        }
        // String and any other type: no silent coercion.
        throw new IllegalArgumentException(
                "Expected integer at " + path + " but got "
                        + value.getClass().getSimpleName() + ": " + value);
    }

    /** Collect the string-typed keys of a map (for drop-logging). */
    private static Set<String> stringKeys(Map<?, ?> map) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (Object k : map.keySet()) {
            keys.add(String.valueOf(k));
        }
        return keys;
    }

    /** Debug-log any key in {@code present} not in {@code allowed} (dropped
     *  by the projection). Forward-compat: ignore unknown, do not persist. */
    private static void logDroppedKeys(Set<String> present, Set<String> allowed, String path) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (String key : present) {
            if (!allowed.contains(key)) {
                log.debug("Dropping off-contract outdated-software key '{}' at {} (forward-compat: ignored, not persisted)",
                        key, path + "." + key);
            }
        }
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
