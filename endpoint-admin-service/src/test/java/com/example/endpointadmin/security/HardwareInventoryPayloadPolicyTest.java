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
 * BE-022 — unit tests for {@link HardwareInventoryPayloadPolicy}
 * (Faz 22.5). Codex {@code 019e7007} post-impl iter-1 absorb.
 *
 * <p>Tests are organized into three buckets:
 * <ol>
 *   <li>STRIP behavior — sensitive hardware identifiers are replaced
 *       with {@code <redacted>} (BIOS serial, user paths, SIDs,
 *       machine GUIDs).</li>
 *   <li>REJECT behavior — secrets in any form (key-name or value
 *       pattern) cause a fail-closed {@link IllegalArgumentException}.</li>
 *   <li>Schema validation — schema version, numeric ranges, array
 *       shapes, MAC normalization.</li>
 * </ol>
 */
class HardwareInventoryPayloadPolicyTest {

    private final HardwareInventoryPayloadPolicy policy = new HardwareInventoryPayloadPolicy();

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void allowsCleanHardwarePayload() {
        Map<String, Object> details = wrapHardware(cleanHardware());

        Map<String, Object> sanitized = policy.sanitize(details);

        assertThat(sanitized).isNotNull();
        Map<String, Object> hw = hardwareOf(sanitized);
        // Stable scalars survive untouched.
        assertThat(hw.get("cpuModel")).isEqualTo("Intel i7-1260P");
        assertThat(hw.get("cpuCores")).isEqualTo(12);
        assertThat(hw.get("ramTotalBytes")).isEqualTo(34359738368L);
        assertThat(hw.get("osName")).isEqualTo("Windows 11");
    }

    @Test
    void doesNotMutateInputMap() {
        Map<String, Object> originalHw = cleanHardware();
        originalHw.put("biosSerial", "ABCD1234");
        Map<String, Object> details = wrapHardware(originalHw);

        Map<String, Object> sanitized = policy.sanitize(details);

        // Original input is untouched; sanitized return value carries
        // the redaction.
        assertThat(hardwareOf(details).get("biosSerial")).isEqualTo("ABCD1234");
        assertThat(hardwareOf(sanitized).get("biosSerial"))
                .isEqualTo(HardwareInventoryPayloadPolicy.REDACTED);
    }

    @Test
    void leavesSoftwareSubtreeUntouchedForExternalValidator() {
        // Regression: the hardware sanitizer must not mutate the
        // software sub-tree — that remains the software policy's
        // authority. A software-side fail-closed reject must still
        // fire if the operator validates it after our sanitize.
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("software", Map.of(
                "appCount", 1,
                "apps", List.of(Map.of(
                        "displayName", "Office",
                        "licenseKey", "test-fake-license-marker"
                ))));
        inventory.put("hardware", cleanHardware());
        details.put("inventory", inventory);

        Map<String, Object> sanitized = policy.sanitize(details);

        // Hardware sub-tree sanitized; software sub-tree untouched.
        @SuppressWarnings("unchecked")
        Map<String, Object> softwareOut = (Map<String, Object>) (
                (Map<String, Object>) sanitized.get("inventory")).get("software");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) softwareOut.get("apps");
        assertThat(apps.get(0).get("licenseKey"))
                .isEqualTo("test-fake-license-marker");
        // External software validator can now reject this licenseKey
        // (validated by software policy in its own integration test).
    }

    // ------------------------------------------------------------------
    // Redaction-boundary type-confusion bypass — a present-but-non-Map
    // hardware block (List / String / scalar) used to skip the Map-gated
    // serial-STRIP / MAC-normalization entirely. Now fail-closed (mirrors
    // WinGetEgressPayloadPolicy.validate). Absent / null stays the opt-out.
    // ------------------------------------------------------------------

    @Test
    void rejectsInventoryHardwareAsListCarryingRawSerialAndMac() {
        Map<String, Object> leak = new LinkedHashMap<>();
        leak.put("biosSerial", "BIOS-SN-0099887766");
        leak.put("macAddress", "AA-BB-CC-DD-EE-FF");
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", List.of(leak));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.inventory.hardware")
                .hasMessageContaining("must be an object");
    }

    @Test
    void rejectsInventoryHardwareAsString() {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", "biosSerial=BIOS-SN-0099887766");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.inventory.hardware")
                .hasMessageContaining("must be an object");
    }

    @Test
    void rejectsTopLevelHardwareAliasAsList() {
        Map<String, Object> leak = new LinkedHashMap<>();
        leak.put("biosSerial", "BIOS-SN-0099887766");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hardware", List.of(leak));

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.hardware")
                .hasMessageContaining("must be an object");
    }

    @Test
    void absentHardwareStaysValidOptOut() {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("software", Map.of("schemaVersion", 1));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void explicitNullHardwareStaysValidOptOut() {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);
        details.put("hardware", null);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // STRIP behavior — sensitive hardware identifiers
    // ------------------------------------------------------------------

    @Test
    void stripsBiosSerialKey() {
        Map<String, Object> hw = cleanHardware();
        hw.put("biosSerial", "BIOSSERIAL12345");
        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        assertThat(hardwareOf(sanitized).get("biosSerial"))
                .isEqualTo(HardwareInventoryPayloadPolicy.REDACTED);
    }

    @Test
    void stripsDiskSerialKeyInNestedDisk() {
        Map<String, Object> hw = cleanHardware();
        List<Map<String, Object>> disks = new ArrayList<>();
        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("devicePath", "/dev/sda");
        disk.put("model", "Samsung 980 Pro");
        disk.put("diskSerial", "SAMSUNG-DSK-SN-001");
        disks.add(disk);
        hw.put("disks", disks);

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sanitizedDisks =
                (List<Map<String, Object>>) hardwareOf(sanitized).get("disks");
        assertThat(sanitizedDisks.get(0).get("diskSerial"))
                .isEqualTo(HardwareInventoryPayloadPolicy.REDACTED);
        assertThat(sanitizedDisks.get(0).get("model")).isEqualTo("Samsung 980 Pro");
    }

    @Test
    void stripsMachineGuidKey() {
        Map<String, Object> hw = cleanHardware();
        hw.put("machineGuid", "{12345678-90AB-CDEF-1234-567890ABCDEF}");
        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        assertThat(hardwareOf(sanitized).get("machineGuid"))
                .isEqualTo(HardwareInventoryPayloadPolicy.REDACTED);
    }

    @Test
    void stripsUserPathInWindowsValue() {
        Map<String, Object> hw = cleanHardware();
        Map<String, Object> probeErrorEntry = new LinkedHashMap<>();
        probeErrorEntry.put("code", "PROBE_USER_PATH");
        probeErrorEntry.put("summary", "Detected on C:\\Users\\alice\\AppData");
        hw.put("probeErrors", List.of(probeErrorEntry));

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> probeErrors =
                (List<Map<String, Object>>) hardwareOf(sanitized).get("probeErrors");
        assertThat(probeErrors.get(0).get("summary")).asString()
                .contains(HardwareInventoryPayloadPolicy.REDACTED)
                .doesNotContain("alice");
    }

    @Test
    void stripsUnixUserPathInValue() {
        Map<String, Object> hw = cleanHardware();
        hw.put("notes", "system lives at /home/bob/.config");

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        assertThat(hardwareOf(sanitized).get("notes")).asString()
                .contains(HardwareInventoryPayloadPolicy.REDACTED)
                .doesNotContain("bob");
    }

    @Test
    void stripsWindowsSidInValue() {
        Map<String, Object> hw = cleanHardware();
        hw.put("owner",
                "S-1-5-21-1234567890-1234567890-1234567890-1001");

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        assertThat(hardwareOf(sanitized).get("owner")).asString()
                .contains(HardwareInventoryPayloadPolicy.REDACTED);
    }

    @Test
    void stripsBracedMachineGuidInValue() {
        Map<String, Object> hw = cleanHardware();
        hw.put("notes", "machine id {12345678-90AB-CDEF-1234-567890ABCDEF} observed");

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        assertThat(hardwareOf(sanitized).get("notes")).asString()
                .contains(HardwareInventoryPayloadPolicy.REDACTED)
                .doesNotContain("12345678-90AB");
    }

    // ------------------------------------------------------------------
    // REJECT behavior — secrets in key or value form
    // ------------------------------------------------------------------

    @Test
    void rejectsTokenKey() {
        Map<String, Object> hw = cleanHardware();
        hw.put("token", "anything");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void rejectsPasswordKey() {
        Map<String, Object> hw = cleanHardware();
        hw.put("password", "hunter2");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void rejectsBearerInValuePosition() {
        // Codex 019e7007 post-impl iter-1 must-fix #3 — value-level
        // secret pattern reject. A probe-error summary carrying
        // "Bearer eyJ..." must fail closed.
        Map<String, Object> hw = cleanHardware();
        Map<String, Object> probeErrorEntry = new LinkedHashMap<>();
        probeErrorEntry.put("code", "AUTH_FAIL");
        probeErrorEntry.put("summary",
                "auth header rejected: Bearer abc123def456ghi789jkl");
        hw.put("probeErrors", List.of(probeErrorEntry));

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    @Test
    void rejectsCompactJwtInValuePosition() {
        Map<String, Object> hw = cleanHardware();
        // Synthetic 3-segment JWT shape — eyJ + 2 base64url segments.
        hw.put("notes", "received eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghi");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    @Test
    void rejectsPasswordKvLeakInValuePosition() {
        Map<String, Object> hw = cleanHardware();
        hw.put("notes", "connection string password=hunter2supersecret");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    @Test
    void rejectsApiKeyKvLeakInValuePosition() {
        Map<String, Object> hw = cleanHardware();
        // Test marker — explicit synthetic value so gitleaks does
        // not flag this fixture as a real api-key leak (PR #310
        // CI absorb pattern from SoftwareInventoryPayloadPolicyTest).
        hw.put("notes", "api_key=test-fake-fixture-marker-not-a-real-secret");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    // ------------------------------------------------------------------
    // Schema validation
    // ------------------------------------------------------------------

    @Test
    void rejectsUnsupportedSchemaVersion() {
        Map<String, Object> hw = cleanHardware();
        hw.put("schemaVersion", 99);

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void rejectsNegativeRamTotalBytes() {
        Map<String, Object> hw = cleanHardware();
        hw.put("ramTotalBytes", -1L);

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Negative value");
    }

    @Test
    void rejectsNegativeCpuFrequencyMhz() {
        Map<String, Object> hw = cleanHardware();
        hw.put("cpuFrequencyMhz", -2400);

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Negative value");
    }

    @Test
    void rejectsDisksNotArray() {
        Map<String, Object> hw = cleanHardware();
        hw.put("disks", "not-an-array");

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected array");
    }

    @Test
    void rejectsNetworkInterfacesNotArray() {
        Map<String, Object> hw = cleanHardware();
        hw.put("networkInterfaces", Map.of("oops", "wrong-shape"));

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected array");
    }

    // ------------------------------------------------------------------
    // MAC normalization
    // ------------------------------------------------------------------

    @Test
    void normalizesUppercaseDashMacToLowercaseColon() {
        Map<String, Object> hw = cleanHardware();
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("name", "Ethernet 1");
        nic.put("macAddress", "AA-BB-CC-DD-EE-FF");
        hw.put("networkInterfaces", List.of(nic));

        Map<String, Object> sanitized = policy.sanitize(wrapHardware(hw));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nics =
                (List<Map<String, Object>>) hardwareOf(sanitized).get("networkInterfaces");
        assertThat(nics.get(0).get("macAddress")).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void rejectsInvalidMacFormat() {
        Map<String, Object> hw = cleanHardware();
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("name", "Ethernet 1");
        nic.put("macAddress", "not-a-mac");
        hw.put("networkInterfaces", List.of(nic));

        assertThatThrownBy(() -> policy.sanitize(wrapHardware(hw)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid MAC address");
    }

    // ------------------------------------------------------------------
    // Null safety
    // ------------------------------------------------------------------

    @Test
    void nullDetailsReturnsNull() {
        assertThatCode(() -> policy.sanitize(null)).doesNotThrowAnyException();
        assertThat(policy.sanitize(null)).isNull();
    }

    @Test
    void detailsWithoutHardwareSubtreeReturnsCopy() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("software", Map.of("appCount", 0, "apps", List.of())));

        Map<String, Object> sanitized = policy.sanitize(details);

        assertThat(sanitized).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> inv = (Map<String, Object>) sanitized.get("inventory");
        assertThat(inv).doesNotContainKey("hardware");
    }

    @Test
    void topLevelHardwareAliasAlsoSanitized() {
        // Some agent versions place hardware at the top level — the
        // policy must catch both placements.
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> hw = cleanHardware();
        hw.put("biosSerial", "TOPLEVEL12345");
        details.put("hardware", hw);

        Map<String, Object> sanitized = policy.sanitize(details);

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedHw = (Map<String, Object>) sanitized.get("hardware");
        assertThat(sanitizedHw.get("biosSerial"))
                .isEqualTo(HardwareInventoryPayloadPolicy.REDACTED);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Build a clean hardware sub-tree map with realistic scalars. */
    private Map<String, Object> cleanHardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("schemaVersion", 1);
        hw.put("supported", true);
        hw.put("cpuModel", "Intel i7-1260P");
        hw.put("cpuCores", 12);
        hw.put("cpuFrequencyMhz", 2400);
        hw.put("ramTotalBytes", 34359738368L);
        hw.put("ramAvailableBytes", 12000000000L);
        hw.put("osName", "Windows 11");
        hw.put("osVersion", "10.0.22631");
        hw.put("osArch", "x64");
        hw.put("biosVendor", "Phoenix");
        hw.put("biosVersion", "1.0.4");
        hw.put("manufacturer", "Dell");
        hw.put("systemModel", "Latitude 7430");
        hw.put("domainJoined", false);
        hw.put("collectedAt", "2026-05-28T10:00:00Z");
        return hw;
    }

    private Map<String, Object> wrapHardware(Map<String, Object> hardware) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", hardware);
        details.put("inventory", inventory);
        return details;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hardwareOf(Map<String, Object> sanitized) {
        return (Map<String, Object>) ((Map<String, Object>) sanitized.get("inventory"))
                .get("hardware");
    }
}
