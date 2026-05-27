package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-021A — fail-closed schema + PII tests for the AG-026A
 * {@code wingetEgress} block (Faz 22.5).
 *
 * <p>Covers Codex 019e6b88 plan-time AGREE acceptance:
 *
 * <ul>
 *   <li>Schema version pinned to {@link WinGetEgressPayloadPolicy#ACCEPTED_SCHEMA_VERSION}</li>
 *   <li>Unknown top-level / sub-shape keys rejected</li>
 *   <li>Forbidden PII keys ({@code token}, {@code licenseKey}, ...) rejected</li>
 *   <li>Forbidden value patterns (Windows SID, {@code C:\Users\...} path, raw MSI GUID) rejected</li>
 *   <li>Sub-shapes (sources, packageQuery, egress.{dns,tcp,https}) validated</li>
 *   <li>Well-formed AG-026A payload passes cleanly</li>
 * </ul>
 */
class WinGetEgressPayloadPolicyTest {

    private final WinGetEgressPayloadPolicy policy =
            new WinGetEgressPayloadPolicy(new SoftwareInventoryPayloadPolicy());

    @Test
    void wellFormedEgressPayloadPasses() {
        Map<String, Object> egress = wellFormedEgress();

        policy.validate(egress);
        // No exception == pass.
        assertThat(egress).isNotEmpty();
    }

    @Test
    void nullEgressPayloadIsTolerated() {
        // The BE-020I-only legacy ingest path passes wingetEgress=null;
        // the validator must accept it as a no-op.
        policy.validate(null);
    }

    @Test
    void schemaVersionMissingIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.remove("schemaVersion");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void schemaVersionMismatchIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("schemaVersion", 999);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void schemaVersionAsStringIsRejected() {
        // Codex 019e6ba4 iter-2 absorb: schemaVersion is strictly
        // integral; "1" as a string is wire-shape drift.
        Map<String, Object> egress = wellFormedEgress();
        egress.put("schemaVersion", "1");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void schemaVersionAsDecimalIsRejected() {
        // 1.0 as a Double would have been truncated to 1 by the old
        // `Number.intValue()` path; the new strict check rejects any
        // non-integral payload.
        Map<String, Object> egress = wellFormedEgress();
        egress.put("schemaVersion", 1.0);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void schemaVersionAsFractionalIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("schemaVersion", 1.5);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void unknownTopLevelKeyIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("rogueFutureField", true);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown wingetEgress field");
    }

    @Test
    void unknownPackageQuerySubKeyIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> pq = new LinkedHashMap<>(asMap(egress.get("packageQuery")));
        pq.put("secretField", "value");
        egress.put("packageQuery", pq);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown wingetEgress field");
    }

    @Test
    void forbiddenPiiKeyAnywhereIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        // Smuggle a forbidden key inside the sources subtree.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "winget");
        source.put("argument", "https://cdn.winget.microsoft.com/cache");
        source.put("token", "abc123"); // forbidden by SoftwareInventoryPayloadPolicy
        egress.put("sources", List.of(source));

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void forbiddenSidValuePatternIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> pq = new LinkedHashMap<>(asMap(egress.get("packageQuery")));
        pq.put("errorReason", "Failed for S-1-5-21-1234567890-1234567890-1234567890-1001");
        egress.put("packageQuery", pq);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SID");
    }

    @Test
    void forbiddenUsersPathInProbeErrorIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("probeError", "Path traversal at C:\\Users\\halilkocoglu\\AppData");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Per-user Windows path");
    }

    @Test
    void rawMsiGuidInEgressIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("probeError",
                "MSI ProductCode leak: {12345678-1234-1234-1234-1234567890AB}");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Raw MSI ProductCode GUID");
    }

    @Test
    void nonMapSourcesIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("sources", "should-be-array");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an array");
    }

    // ────────────────────────────────────────────────────────────────
    // Codex 019e6ba4 iter-1 absorb — required-field + pin tests

    @Test
    void missingPackageQueryIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.remove("packageQuery");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packageQuery");
    }

    @Test
    void missingEgressIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.remove("egress");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("egress");
    }

    @Test
    void missingSupportedIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.remove("supported");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported");
    }

    @Test
    void supportedAsStringIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("supported", "true");

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported");
    }

    @Test
    void uppercaseKeyIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        egress.put("PackageQuery", egress.remove("packageQuery"));

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown wingetEgress field");
    }

    @Test
    void packageQueryWrongPackageIdIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> pq = new LinkedHashMap<>(asMap(egress.get("packageQuery")));
        pq.put("packageId", "Notepad.Notepad");
        egress.put("packageQuery", pq);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7zip.7zip");
    }

    @Test
    void packageQueryMissingPackageIdIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> pq = new LinkedHashMap<>(asMap(egress.get("packageQuery")));
        pq.remove("packageId");
        egress.put("packageQuery", pq);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("packageId");
    }

    @Test
    void egressMissingProxyConfiguredIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> egressBlock = new LinkedHashMap<>(asMap(egress.get("egress")));
        egressBlock.remove("proxyConfigured");
        egress.put("egress", egressBlock);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxyConfigured");
    }

    @Test
    void supportedTrueRequiresDnsTcpHttpsArrays() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> egressBlock = new LinkedHashMap<>(asMap(egress.get("egress")));
        egressBlock.remove("dns");
        egress.put("egress", egressBlock);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dns");
    }

    @Test
    void supportedFalseTolerantOfEmptyEgressArrays() {
        // Non-Windows stub: supported=false, empty arrays — the validator
        // accepts this even without DNS/TCP/HTTPS check rows. The
        // install-preflight service still BLOCKs on winget_not_ready
        // / inventory_unsupported before reaching here.
        Map<String, Object> egress = wellFormedEgress();
        egress.put("supported", false);
        Map<String, Object> egressBlock = new LinkedHashMap<>(asMap(egress.get("egress")));
        egressBlock.put("dns", List.of());
        egressBlock.put("tcp", List.of());
        egressBlock.put("https", List.of());
        egress.put("egress", egressBlock);

        policy.validate(egress);
    }

    @Test
    void networkCheckUnknownSubKeyIsRejected() {
        Map<String, Object> egress = wellFormedEgress();
        Map<String, Object> egressBlock =
                new LinkedHashMap<>(asMap(egress.get("egress")));
        List<Map<String, Object>> dnsList = List.of(Map.of(
                "target", "cdn.winget.microsoft.com",
                "ok", true,
                "durationMs", 12,
                "unknownExtra", "value"
        ));
        egressBlock.put("dns", dnsList);
        egress.put("egress", egressBlock);

        assertThatThrownBy(() -> policy.validate(egress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown wingetEgress field");
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers

    private static Map<String, Object> wellFormedEgress() {
        Map<String, Object> egress = new LinkedHashMap<>();
        egress.put("supported", true);
        egress.put("schemaVersion", 1);
        egress.put("probeDurationMs", 4380);
        egress.put("timeout", false);

        egress.put("sources", List.of(
                Map.of(
                        "name", "winget",
                        "argument", "https://cdn.winget.microsoft.com/cache",
                        "type", "Microsoft.PreIndexed.Package",
                        "trustLevel", "Trusted"
                ),
                Map.of(
                        "name", "msstore",
                        "argument", "https://storeedgefd.dsx.mp.microsoft.com/v9.0",
                        "type", "Microsoft.Rest",
                        "trustLevel", "Trusted"
                )
        ));

        Map<String, Object> packageQuery = new LinkedHashMap<>();
        packageQuery.put("packageId", "7zip.7zip");
        packageQuery.put("found", true);
        packageQuery.put("exitCode", 0);
        packageQuery.put("durationMs", 1820);
        packageQuery.put("timeout", false);
        egress.put("packageQuery", packageQuery);

        Map<String, Object> egressBlock = new LinkedHashMap<>();
        egressBlock.put("dns", List.of(
                Map.of("target", "cdn.winget.microsoft.com",
                        "ok", true, "durationMs", 12),
                Map.of("target", "storeedgefd.dsx.mp.microsoft.com",
                        "ok", true, "durationMs", 14)));
        egressBlock.put("tcp", List.of(
                Map.of("target", "cdn.winget.microsoft.com:443",
                        "ok", true, "durationMs", 38),
                Map.of("target", "storeedgefd.dsx.mp.microsoft.com:443",
                        "ok", true, "durationMs", 41)));
        egressBlock.put("https", List.of(
                Map.of("target", "https://cdn.winget.microsoft.com",
                        "ok", true, "durationMs", 152),
                Map.of("target", "https://storeedgefd.dsx.mp.microsoft.com",
                        "ok", true, "durationMs", 167)));
        egressBlock.put("proxyConfigured", false);
        egress.put("egress", egressBlock);

        return egress;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
