package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BE-021A — fail-closed schema + PII policy for the agent
 * {@code inventory.wingetEgress} block produced by AG-026A (Faz 22.5).
 *
 * <p>Runs <strong>before</strong> the parent
 * {@code endpoint_command_results} row is persisted, immediately after
 * the existing {@link SoftwareInventoryPayloadPolicy} pass (Codex
 * 019e6b88 plan-time AGREE). Failure throws
 * {@link IllegalArgumentException}; the
 * {@code EndpointAgentCommandService.submitResult} caller translates
 * that into a 400 response via the existing handler convention.
 *
 * <p>Two-layer enforcement:
 *
 * <ol>
 *   <li><b>Schema allowlist</b>: the {@code wingetEgress} block is
 *       version-pinned to {@link #ACCEPTED_SCHEMA_VERSION} (AG-026A
 *       schema=1). Unknown top-level keys are rejected so a future
 *       agent build that adds a field gets a fail-closed signal
 *       instead of silently widening the persisted JSONB. Sub-shapes
 *       ({@code sources[]}, {@code packageQuery}, {@code egress})
 *       carry their own allowlists.</li>
 *   <li><b>PII / secret scan</b>: every string value in the egress
 *       sub-tree is re-checked through the same regex set
 *       {@link SoftwareInventoryPayloadPolicy} uses for the
 *       {@code software} block (raw MSI GUID, {@code C:\\Users\\...}
 *       paths, Windows SID literals, forbidden keys like
 *       {@code licenseKey} / {@code token} / {@code password}). The
 *       agent redacts these before shipping, but backend revalidates
 *       fail-closed because BE-020I + BE-021A operate on
 *       defence-in-depth: agent compromise must not turn into a
 *       persisted-PII regression.</li>
 * </ol>
 *
 * <p>Allowlist (top-level + recursive children) tracks the canonical
 * AG-026A wire shape:
 *
 * <pre>{@code
 * wingetEgress
 *   ├─ supported              (bool, required)
 *   ├─ schemaVersion          (int, required, == ACCEPTED_SCHEMA_VERSION)
 *   ├─ probeDurationMs        (number, required)
 *   ├─ timeout                (bool, required)
 *   ├─ probeError             (string, optional)
 *   ├─ sources                (array<SourceInfo>, optional)
 *   ├─ sourceListError        (string, optional)
 *   ├─ packageQuery           (PackageQueryResult, required)
 *   └─ egress                 (EgressSummary, required)
 *
 * SourceInfo: name, argument, type, trustLevel, explicit
 * PackageQueryResult: packageId, found, exitCode, durationMs, timeout, errorReason
 * NetworkCheck: target, ok, durationMs, errorReason
 * EgressSummary: dns[], tcp[], https[], proxyConfigured, proxyUrl
 * }</pre>
 */
@Component
public class WinGetEgressPayloadPolicy {

    /**
     * AG-026A canonical schema version. A future bump SHOULD ship as a
     * paired backend change that relaxes this validator AND the V9
     * Flyway CHECK constraint, so the contract stays authoritative
     * for the live agent fleet.
     */
    public static final int ACCEPTED_SCHEMA_VERSION = 1;

    /**
     * AG-026A pilot package id. The agent's
     * {@code FixedPackageQueryID} is hard-coded to this value; the
     * backend revalidates the wire field at ingest time so a future
     * agent build that probes a different package gets a fail-closed
     * signal here (Codex 019e6ba4 iter-1 absorb).
     */
    public static final String ACCEPTED_PACKAGE_ID = "7zip.7zip";

    private static final Set<String> WINGET_EGRESS_KEYS = Set.of(
            "supported",
            "schemaVersion",
            "probeDurationMs",
            "timeout",
            "probeError",
            "sources",
            "sourceListError",
            "packageQuery",
            "egress");

    private static final Set<String> SOURCE_INFO_KEYS = Set.of(
            "name",
            "argument",
            "type",
            "trustLevel",
            "explicit");

    private static final Set<String> PACKAGE_QUERY_KEYS = Set.of(
            "packageId",
            "found",
            "exitCode",
            "durationMs",
            "timeout",
            "errorReason");

    private static final Set<String> EGRESS_SUMMARY_KEYS = Set.of(
            "dns",
            "tcp",
            "https",
            "proxyConfigured",
            "proxyUrl");

    private static final Set<String> NETWORK_CHECK_KEYS = Set.of(
            "target",
            "ok",
            "durationMs",
            "errorReason");

    private final SoftwareInventoryPayloadPolicy softwareInventoryPolicy;

    @Autowired
    public WinGetEgressPayloadPolicy(SoftwareInventoryPayloadPolicy softwareInventoryPolicy) {
        this.softwareInventoryPolicy = softwareInventoryPolicy;
    }

    /**
     * Validate the {@code inventory.wingetEgress} sub-map.
     *
     * <p>Caller responsibility: extract the {@code wingetEgress} value
     * from the {@code details.inventory} parent map and pass it here.
     * The parent {@code SoftwareInventoryPayloadPolicy} pass runs over
     * the entire {@code details} tree first; this validator focuses
     * on the egress-specific schema invariants.
     *
     * <p>Codex 019e6ba4 iter-1 absorb — fail-closed required-field
     * enforcement (the previous "optional everywhere" shape would have
     * let a malformed AG-026A payload reach the install-preflight
     * decision matrix with a missing {@code egress} block and still
     * resolve to PASS):
     *
     * <ul>
     *   <li>Top-level required: {@code supported} (boolean),
     *       {@code schemaVersion} (int == 1), {@code probeDurationMs}
     *       (number), {@code timeout} (boolean), {@code packageQuery}
     *       (object), {@code egress} (object).</li>
     *   <li>{@code packageQuery} required sub-keys: {@code packageId}
     *       (== {@link #ACCEPTED_PACKAGE_ID}), {@code found} (boolean),
     *       {@code exitCode} (int), {@code durationMs} (number),
     *       {@code timeout} (boolean).</li>
     *   <li>{@code egress} required sub-keys: {@code proxyConfigured}
     *       (boolean). DNS / TCP / HTTPS arrays are required when
     *       {@code supported=true} (a healthy AG-026A run always emits
     *       them — non-Windows stubs ship {@code supported=false}
     *       which is BLOCK in the service gate).</li>
     *   <li>Key matching is case-sensitive verbatim against the
     *       AG-026A wire shape — no case-insensitive tolerance (Codex
     *       019e6ba4 iter-1: drift detection beats compatibility).</li>
     * </ul>
     *
     * @param wingetEgress the {@code inventory.wingetEgress} sub-map,
     *                     or {@code null} if the agent did not ship the
     *                     block (which is the BE-020I-only legacy path).
     */
    public void validate(Object wingetEgress) {
        if (wingetEgress == null) {
            return;
        }
        if (!(wingetEgress instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(
                    "wingetEgress must be an object, got "
                            + wingetEgress.getClass().getSimpleName());
        }
        // Defence-in-depth: re-run the parent PII scan over this subtree
        // even though the caller is expected to have validated the full
        // tree. A future caller that calls this validator directly
        // (e.g. in a unit test) gets the full coverage.
        softwareInventoryPolicy.validate(root);

        assertOnlyKnownKeys(root, WINGET_EGRESS_KEYS, "$.inventory.wingetEgress");
        // Required + type-pinned top-level fields. Order matters for
        // clear error messages — schemaVersion is checked first because
        // a wrong version invalidates every other assumption.
        assertSchemaVersionPinned(root.get("schemaVersion"));
        requireBoolean(root, "supported", "$.inventory.wingetEgress");
        requireNumber(root, "probeDurationMs", "$.inventory.wingetEgress");
        requireBoolean(root, "timeout", "$.inventory.wingetEgress");

        boolean supported = (Boolean) root.get("supported");

        // Sources is OPTIONAL (an empty source list is a legitimate
        // readiness signal — `BLOCK winget_not_ready` would already
        // have fired in the service gate). Type pin: must be an array
        // when present.
        Object sources = root.get("sources");
        if (sources instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                String path = "$.inventory.wingetEgress.sources[" + (i++) + "]";
                if (!(element instanceof Map<?, ?> source)) {
                    throw new IllegalArgumentException(
                            "wingetEgress.sources element must be an object at " + path);
                }
                assertOnlyKnownKeys(source, SOURCE_INFO_KEYS, path);
            }
        } else if (sources != null) {
            throw new IllegalArgumentException(
                    "wingetEgress.sources must be an array, got "
                            + sources.getClass().getSimpleName());
        }

        // packageQuery is REQUIRED. Required sub-keys validated below
        // so a malformed sub-shape cannot reach the install-preflight
        // service with PASS-eligible evidence.
        Object packageQuery = root.get("packageQuery");
        if (!(packageQuery instanceof Map<?, ?> pq)) {
            throw new IllegalArgumentException(
                    "wingetEgress.packageQuery is required and must be an object.");
        }
        assertOnlyKnownKeys(pq, PACKAGE_QUERY_KEYS, "$.inventory.wingetEgress.packageQuery");
        Object packageId = pq.get("packageId");
        if (!(packageId instanceof String pid)) {
            throw new IllegalArgumentException(
                    "wingetEgress.packageQuery.packageId is required (string).");
        }
        if (!ACCEPTED_PACKAGE_ID.equals(pid)) {
            // AG-026A is hard-coded to the pilot package. Anything else
            // is wire-shape drift that BE-021A cannot use as PASS
            // evidence; the install-preflight service additionally
            // emits BLOCK `winget_fixed_probe_package_mismatch` when
            // the catalog item's packageId doesn't match the pilot id,
            // but here we reject at ingest time because a non-pilot
            // packageId means the agent shipped output it had no
            // business emitting.
            throw new IllegalArgumentException(
                    "wingetEgress.packageQuery.packageId must be "
                            + ACCEPTED_PACKAGE_ID + " (got " + pid + ").");
        }
        requireBoolean(pq, "found", "$.inventory.wingetEgress.packageQuery");
        requireNumber(pq, "exitCode", "$.inventory.wingetEgress.packageQuery");
        requireNumber(pq, "durationMs", "$.inventory.wingetEgress.packageQuery");
        requireBoolean(pq, "timeout", "$.inventory.wingetEgress.packageQuery");

        // egress is REQUIRED. proxyConfigured boolean required.
        // DNS/TCP/HTTPS arrays REQUIRED when supported=true (a
        // supported AG-026A run on Windows always emits them; the
        // non-Windows stub ships supported=false with empty arrays
        // and is BLOCKed by the install-preflight service before
        // reaching here in the first place).
        Object egress = root.get("egress");
        if (!(egress instanceof Map<?, ?> es)) {
            throw new IllegalArgumentException(
                    "wingetEgress.egress is required and must be an object.");
        }
        assertOnlyKnownKeys(es, EGRESS_SUMMARY_KEYS, "$.inventory.wingetEgress.egress");
        requireBoolean(es, "proxyConfigured", "$.inventory.wingetEgress.egress");
        if (supported) {
            assertNetworkCheckListRequired(es.get("dns"), "dns");
            assertNetworkCheckListRequired(es.get("tcp"), "tcp");
            assertNetworkCheckListRequired(es.get("https"), "https");
        } else {
            // supported=false: the non-Windows stub legitimately emits
            // empty arrays. The service gate will BLOCK on
            // `winget_not_ready` before checking egress; the validator
            // accepts the empty arrays here but still type-checks them.
            assertNetworkCheckList(es.get("dns"), "dns");
            assertNetworkCheckList(es.get("tcp"), "tcp");
            assertNetworkCheckList(es.get("https"), "https");
        }
    }

    private static void requireBoolean(Map<?, ?> map, String key, String parentPath) {
        Object value = map.get(key);
        if (value == null || !(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    parentPath + "." + key + " is required (boolean).");
        }
    }

    private static void requireNumber(Map<?, ?> map, String key, String parentPath) {
        Object value = map.get(key);
        if (value == null || !(value instanceof Number)) {
            throw new IllegalArgumentException(
                    parentPath + "." + key + " is required (number).");
        }
    }

    private void assertNetworkCheckListRequired(Object list, String fieldName) {
        if (list == null) {
            throw new IllegalArgumentException(
                    "wingetEgress.egress." + fieldName
                            + " is required (array) when supported=true.");
        }
        assertNetworkCheckList(list, fieldName);
    }

    /**
     * Schema version pin — Codex 019e6ba4 iter-2 absorb: strict
     * integral-only check, no string coercion, no decimal truncation.
     *
     * <p>Earlier iterations accepted {@code "1"} as a string and
     * {@code 1.5} as a {@code Double} (truncated to {@code 1}); both
     * paths violated the "verbatim AG-026A wire shape" contract.
     * Drift detection beats compatibility — a future agent build that
     * ships {@code schemaVersion: "1"} or {@code 1.0} as a string is
     * already a wire-shape regression and gets fail-closed rejected
     * here.
     */
    private void assertSchemaVersionPinned(Object schemaVersion) {
        if (schemaVersion == null) {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion is required (expected "
                            + ACCEPTED_SCHEMA_VERSION + ", integer).");
        }
        if (!(schemaVersion instanceof Integer || schemaVersion instanceof Long)) {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion must be an integer, got "
                            + schemaVersion.getClass().getSimpleName());
        }
        long actual = ((Number) schemaVersion).longValue();
        if (actual != ACCEPTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion = " + actual
                            + " not supported (expected "
                            + ACCEPTED_SCHEMA_VERSION + ").");
        }
    }

    private void assertNetworkCheckList(Object list, String fieldName) {
        if (list == null) {
            return;
        }
        if (!(list instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(
                    "wingetEgress.egress." + fieldName
                            + " must be an array, got "
                            + list.getClass().getSimpleName());
        }
        int i = 0;
        for (Object element : iterable) {
            String path = "$.inventory.wingetEgress.egress." + fieldName + "[" + (i++) + "]";
            if (!(element instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        "wingetEgress.egress." + fieldName
                                + " element must be an object at " + path);
            }
            assertOnlyKnownKeys(entry, NETWORK_CHECK_KEYS, path);
        }
    }

    /**
     * Case-sensitive verbatim match against the AG-026A wire-shape
     * allowlist (Codex 019e6ba4 iter-1 absorb).
     *
     * <p>Earlier iterations tolerated case-insensitive variants on the
     * theory that "even an uppercase typo is still unknown" — but in
     * practice that masked drift detection. The AG-026A struct emits
     * lowercase field names ({@code supported}, {@code packageQuery},
     * etc.) verbatim; anything else is wire-shape drift and gets
     * fail-closed rejected here.
     */
    private void assertOnlyKnownKeys(Map<?, ?> map, Set<String> allowed, String path) {
        for (Object rawKey : map.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown wingetEgress field '" + key + "' at " + path);
            }
        }
    }
}
