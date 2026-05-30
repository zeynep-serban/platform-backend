package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — unit tests for {@link OutdatedSoftwarePayloadPolicy} (Faz 22.5,
 * AG-036 ingest). Mirrors the AG-033 {@code DeviceHealthPayloadPolicyTest}
 * structure, enforcing the outdated-software contract's redaction boundary
 * (schema/endpoint-outdated-software-payload-v1.schema.json, gitops
 * {@code 73f0db0f}).
 *
 * <p>Buckets:
 * <ol>
 *   <li>Golden corpus — the three contract examples (with-upgrades /
 *       clean / unsupported) validate + survive sanitize verbatim.</li>
 *   <li>Package-facet allowlist — {packageId, installedVersion,
 *       availableVersion} only; any forbidden package key (name / publisher
 *       / path / license / url) is fail-closed rejected; packageId pattern
 *       ({@code ^\S+$}) enforced; version length bounded.</li>
 *   <li>Schema / enum / const / range validation.</li>
 *   <li>Secret reject (key + value pattern) + identifier strip + unknown
 *       top-level key drop (forward-compat).</li>
 * </ol>
 */
class OutdatedSoftwarePayloadPolicyTest {

    private final OutdatedSoftwarePayloadPolicy policy = new OutdatedSoftwarePayloadPolicy();

    // ------------------------------------------------------------------
    // Golden corpus — the contract's three examples
    // ------------------------------------------------------------------

    @Test
    void goldenWithUpgradesExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenWithUpgrades()));
        Map<String, Object> os = osOf(sanitized);

        assertThat(os.get("supported")).isEqualTo(true);
        assertThat(os.get("probeComplete")).isEqualTo(true);
        assertThat(os.get("upgradeCount")).isEqualTo(2);
        assertThat(os.get("upgradeTruncated")).isEqualTo(false);
        assertThat(os.get("maxUpgrade")).isEqualTo(512);
        assertThat(os.get("sourceUsed")).isEqualTo("winget");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        assertThat(upgrade).hasSize(2);
        assertThat(upgrade.get(0)).containsOnlyKeys("packageId", "installedVersion", "availableVersion");
        assertThat(upgrade.get(0).get("packageId")).isEqualTo("7zip.7zip");
        assertThat(upgrade.get(0).get("installedVersion")).isEqualTo("24.09");
        assertThat(upgrade.get(0).get("availableVersion")).isEqualTo("25.01");
        assertThat(upgrade.get(1).get("packageId")).isEqualTo("Microsoft.VisualStudioCode");
    }

    @Test
    void goldenCleanExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenClean()));
        Map<String, Object> os = osOf(sanitized);

        assertThat(os.get("upgradeCount")).isEqualTo(0);
        assertThat(os.get("sourceUsed")).isEqualTo("winget");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        assertThat(upgrade).isEmpty();
        // upgrade ALWAYS serializes as [] (never null).
        assertThat(upgrade).isNotNull();
    }

    @Test
    void goldenUnsupportedExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenUnsupported()));
        Map<String, Object> os = osOf(sanitized);

        assertThat(os.get("supported")).isEqualTo(false);
        assertThat(os.get("probeComplete")).isEqualTo(false);
        assertThat(os.get("sourceUsed")).isEqualTo("none");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) os.get("probeErrors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("code")).isEqualTo("UNSUPPORTED_PLATFORM");
        assertThat(errors.get(0).get("source")).isEqualTo("none");
    }

    // ------------------------------------------------------------------
    // Package-facet allowlist (the redaction boundary)
    // ------------------------------------------------------------------

    @Test
    void forbiddenPackageDisplayNameKeyIsFailClosedRejected() {
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(0).put("name", "7-Zip");

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden outdated-software package key 'name'");
    }

    @Test
    void forbiddenPackagePublisherKeyIsFailClosedRejected() {
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(1).put("publisher", "Microsoft Corporation");

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden outdated-software package key 'publisher'");
    }

    @Test
    void forbiddenPackageInstallPathAndLicenseAndUrlKeysRejected() {
        for (String forbidden : List.of("installLocation", "license", "downloadUrl", "path")) {
            Map<String, Object> os = goldenWithUpgrades();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
            upgrade.get(0).put(forbidden, "leak");
            assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                    .as("package key '%s' must be rejected", forbidden)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Forbidden outdated-software package key '" + forbidden + "'");
        }
    }

    @Test
    void packageIdWithWhitespaceRejected() {
        // A display name leaking into packageId would contain spaces — the
        // ^\S+$ pattern rejects it.
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(0).put("packageId", "7-Zip File Manager");

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid packageId");
    }

    @Test
    void packageMissingRequiredVersionRejected() {
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(0).remove("availableVersion");

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableVersion");
    }

    @Test
    void overLengthPackageIdRejected() {
        Map<String, Object> os = goldenWithUpgrades();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> upgrade = (List<Map<String, Object>>) os.get("upgrade");
        upgrade.get(0).put("packageId", "a".repeat(257));

        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLength");
    }

    // ------------------------------------------------------------------
    // Schema / enum / const / range validation
    // ------------------------------------------------------------------

    @Test
    void minimalSchemaVersionOnlyBlockRejected() {
        Map<String, Object> details = wrap(new LinkedHashMap<>(Map.of("schemaVersion", 1)));
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required outdated-software field");
    }

    @Test
    void unsupportedSchemaVersionRejected() {
        Map<String, Object> os = goldenClean();
        os.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported outdated-software schema_version");
    }

    @Test
    void stringSchemaVersionNotCoerced() {
        Map<String, Object> os = goldenClean();
        os.put("schemaVersion", "1");
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected integer");
    }

    @Test
    void invalidSourceUsedRejected() {
        Map<String, Object> os = goldenClean();
        os.put("sourceUsed", "chocolatey");
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported outdated-software sourceUsed='chocolatey'");
    }

    @Test
    void nonConstMaxUpgradeRejected() {
        Map<String, Object> os = goldenClean();
        os.put("maxUpgrade", 1024);
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal 512 (contract const)");
    }

    @Test
    void upgradeCountAboveCapRejected() {
        Map<String, Object> os = goldenClean();
        os.put("upgradeCount", 513);
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds bound (512)");
    }

    @Test
    void negativeUpgradeCountRejected() {
        Map<String, Object> os = goldenClean();
        os.put("upgradeCount", -1);
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Negative value not allowed");
    }

    @Test
    void upgradeArrayOverMaxItemsRejected() {
        Map<String, Object> os = goldenClean();
        // upgradeCount stays at 0 (so the count range CHECK is happy) — the
        // failure is the maxItems cap on the array itself.
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 513; i++) {
            tooMany.add(pkg("pkg.id" + i, "1.0", "2.0"));
        }
        os.put("upgrade", tooMany);
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the contract cap of 512 (maxItems)");
    }

    @Test
    void invalidProbeErrorCodeRejected() {
        Map<String, Object> os = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) os.get("probeErrors");
        errors.get(0).put("code", "NOT_A_CODE");
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported probeError code 'NOT_A_CODE'");
    }

    // ------------------------------------------------------------------
    // Secret reject + identifier strip + forward-compat drop
    // ------------------------------------------------------------------

    @Test
    void secretKeyAnywhereRejected() {
        Map<String, Object> os = goldenClean();
        os.put("token", "abc123");
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden outdated-software key 'token'");
    }

    @Test
    void secretValuePatternInProbeErrorSummaryRejected() {
        Map<String, Object> os = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) os.get("probeErrors");
        errors.get(0).put("summary", "Authorization: Bearer eyJabc.defghij.klmnopqr");
        assertThatThrownBy(() -> policy.sanitize(wrap(os)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern detected");
    }

    @Test
    void unknownTopLevelKeyDroppedNotPersisted() {
        // Forward-compat: an unknown top-level field is dropped, NOT rejected
        // (an older backend accepts a newer agent without a hard 400).
        Map<String, Object> os = goldenClean();
        os.put("futureField", "ignore-me");
        Map<String, Object> sanitized = policy.sanitize(wrap(os));
        Map<String, Object> projected = osOf(sanitized);
        assertThat(projected).doesNotContainKey("futureField");
        assertThat(projected).containsKeys("schemaVersion", "supported", "upgrade", "sourceUsed");
    }

    @Test
    void probeErrorSummaryBoundedToContractMax() {
        Map<String, Object> os = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) os.get("probeErrors");
        errors.get(0).put("summary", "x".repeat(500));

        Map<String, Object> sanitized = policy.sanitize(wrap(os));
        Map<String, Object> projected = osOf(sanitized);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projErrors = (List<Map<String, Object>>) projected.get("probeErrors");
        assertThat((String) projErrors.get(0).get("summary")).hasSize(200);
        assertThat(OutdatedSoftwarePayloadPolicy.SUMMARY_MAX_LEN).isEqualTo(200);
    }

    @Test
    void sanitizeOfNullDetailsIsNull() {
        assertThat(policy.sanitize(null)).isNull();
    }

    @Test
    void detailsWithoutOutdatedSoftwareBlockUntouched() {
        // A details map carrying only hardware/software must pass through
        // without throwing (no outdatedSoftware sub-tree to validate).
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", Map.of("schemaVersion", 1));
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Fixtures — the three contract golden examples (verbatim)
    // ------------------------------------------------------------------

    private Map<String, Object> goldenWithUpgrades() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 2);
        List<Map<String, Object>> upgrade = new ArrayList<>();
        upgrade.add(pkg("7zip.7zip", "24.09", "25.01"));
        upgrade.add(pkg("Microsoft.VisualStudioCode", "1.89.0", "1.91.1"));
        os.put("upgrade", upgrade);
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 45);
        return os;
    }

    private Map<String, Object> goldenClean() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", true);
        os.put("probeComplete", true);
        os.put("upgradeCount", 0);
        os.put("upgrade", new ArrayList<>());
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "winget");
        os.put("probeDurationMs", 28);
        return os;
    }

    private Map<String, Object> goldenUnsupported() {
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("schemaVersion", 1);
        os.put("supported", false);
        os.put("probeComplete", false);
        os.put("upgradeCount", 0);
        os.put("upgrade", new ArrayList<>());
        os.put("upgradeTruncated", false);
        os.put("maxUpgrade", 512);
        os.put("sourceUsed", "none");
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("source", "none");
        err.put("code", "UNSUPPORTED_PLATFORM");
        err.put("summary", "outdated-software probe not supported on this runtime");
        errors.add(err);
        os.put("probeErrors", errors);
        os.put("probeDurationMs", 0);
        return os;
    }

    private static Map<String, Object> pkg(String id, String installed, String available) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("packageId", id);
        p.put("installedVersion", installed);
        p.put("availableVersion", available);
        return p;
    }

    private static Map<String, Object> wrap(Map<String, Object> outdatedSoftware) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("outdatedSoftware", outdatedSoftware);
        details.put("inventory", inventory);
        return details;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> osOf(Map<String, Object> sanitized) {
        return (Map<String, Object>)
                ((Map<String, Object>) sanitized.get("inventory")).get("outdatedSoftware");
    }
}
