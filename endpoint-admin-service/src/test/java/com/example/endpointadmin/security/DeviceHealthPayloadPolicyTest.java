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
 * BE — unit tests for {@link DeviceHealthPayloadPolicy} (Faz 22.5,
 * AG-033 ingest). Mirrors the BE-022 {@code HardwareInventoryPayloadPolicyTest}
 * structure, but enforces the device-health contract's tighter redaction
 * boundary (schema/endpoint-device-health-payload-v1.schema.json).
 *
 * <p>Buckets:
 * <ol>
 *   <li>Golden corpus — the three contract examples (healthy /
 *       warning / unsupported) validate + survive sanitize.</li>
 *   <li>Disk-facet allowlist — driveLetter-only; any forbidden disk key
 *       (label / serial / filesystem / mount / GUID) is fail-closed
 *       rejected; driveLetter pattern enforced.</li>
 *   <li>Schema / enum / range validation.</li>
 *   <li>Secret reject (key + value pattern) + identifier strip.</li>
 * </ol>
 */
class DeviceHealthPayloadPolicyTest {

    private final DeviceHealthPayloadPolicy policy = new DeviceHealthPayloadPolicy();

    // ------------------------------------------------------------------
    // Golden corpus — the contract's three examples
    // ------------------------------------------------------------------

    @Test
    void goldenHealthyExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenHealthy()));
        Map<String, Object> dh = dhOf(sanitized);

        assertThat(dh.get("supported")).isEqualTo(true);
        assertThat(dh.get("probeComplete")).isEqualTo(true);
        assertThat(dh.get("anyLowDisk")).isEqualTo(false);
        assertThat(dh.get("sourceUsed")).isEqualTo("win32");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        assertThat(disks).hasSize(1);
        assertThat(disks.get(0).get("driveLetter")).isEqualTo("C:");
        assertThat(disks.get(0).get("lowDiskWarning")).isEqualTo(false);
    }

    @Test
    void goldenWarningExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenWarning()));
        Map<String, Object> dh = dhOf(sanitized);

        assertThat(dh.get("anyLowDisk")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> mem = (Map<String, Object>) dh.get("memory");
        assertThat(mem.get("highPressureWarning")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) dh.get("uptime");
        assertThat(up.get("longUptimeWarning")).isEqualTo(true);
    }

    @Test
    void goldenUnsupportedExampleValidatesAndSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(goldenUnsupported()));
        Map<String, Object> dh = dhOf(sanitized);

        assertThat(dh.get("supported")).isEqualTo(false);
        assertThat(dh.get("probeComplete")).isEqualTo(false);
        assertThat(dh.get("sourceUsed")).isEqualTo("none");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        assertThat(disks).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("code")).isEqualTo("UNSUPPORTED_PLATFORM");
    }

    @Test
    void doesNotMutateInputMap() {
        Map<String, Object> dh = goldenHealthy();
        Map<String, Object> details = wrap(dh);

        policy.sanitize(details);

        // Original input untouched.
        @SuppressWarnings("unchecked")
        Map<String, Object> origDh = (Map<String, Object>) ((Map<String, Object>)
                details.get("inventory")).get("deviceHealth");
        assertThat(origDh.get("sourceUsed")).isEqualTo("win32");
    }

    // ------------------------------------------------------------------
    // Disk-facet allowlist (the tighter-than-hardware boundary)
    // ------------------------------------------------------------------

    @Test
    void rejectsDiskWithVolumeLabelKey() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("volumeLabel", "OS");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden device-health disk key");
    }

    @Test
    void rejectsDiskWithSerialKey() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("serialNumber", "WD-SN-12345");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden device-health disk key");
    }

    @Test
    void rejectsDiskWithFilesystemOrMountOrGuidKey() {
        for (String forbidden : new String[]{"fileSystem", "mountPath", "guid"}) {
            Map<String, Object> dh = goldenHealthy();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
            disks.get(0).put(forbidden, "anything");

            assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                    .as("disk key '%s' must be rejected", forbidden)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Forbidden device-health disk key");
        }
    }

    @Test
    void rejectsInvalidDriveLetterPattern() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("driveLetter", "C:\\Windows");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid driveLetter");
    }

    @Test
    void rejectsLowercaseDriveLetter() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("driveLetter", "c:");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid driveLetter");
    }

    @Test
    void rejectsMissingDriveLetter() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).remove("driveLetter");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing driveLetter");
    }

    @Test
    void rejectsFixedDisksNotArray() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("fixedDisks", "not-an-array");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected array");
    }

    // ------------------------------------------------------------------
    // Schema / enum / range validation
    // ------------------------------------------------------------------

    @Test
    void rejectsUnsupportedSchemaVersion() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("schemaVersion", 99);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema_version");
    }

    @Test
    void rejectsUnknownSourceUsedEnum() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("sourceUsed", "wmi");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceUsed");
    }

    @Test
    void rejectsNegativeFixedDiskCount() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("fixedDiskCount", -1);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Negative value");
    }

    @Test
    void rejectsMemoryUsedPercentOutOfRange() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> mem = (Map<String, Object>) dh.get("memory");
        mem.put("usedPercent", 150);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Percent out of range");
    }

    @Test
    void rejectsFreePercentOutOfRange() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("freePercent", 101);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Percent out of range");
    }

    @Test
    void rejectsNegativeLastBootEpochSec() {
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) dh.get("uptime");
        up.put("lastBootEpochSec", -5);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Negative value");
    }

    // ------------------------------------------------------------------
    // Schema-fidelity gap (Codex iter-2 REVISE): const / maxItems /
    // strict integer typing + column bounds
    // ------------------------------------------------------------------

    @Test
    void rejectsMaxFixedDisksOtherThan64() {
        // Contract: maxFixedDisks is const 64. A non-negative-but-wrong
        // value (e.g. 128) must NOT slip into the command-result payload
        // + snapshot redacted_payload — fail-closed reject.
        Map<String, Object> dh = goldenHealthy();
        dh.put("maxFixedDisks", 128);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal 64");

        // The const-64 golden value still validates (no regression).
        Map<String, Object> ok = goldenHealthy();
        assertThatCode(() -> policy.sanitize(wrap(ok))).doesNotThrowAnyException();
    }

    @Test
    void rejectsFixedDisksAbove64() {
        // Contract: fixedDisks has maxItems 64. A 65-element array is
        // fail-closed rejected (no per-disk projection happens).
        Map<String, Object> dh = goldenHealthy();
        List<Map<String, Object>> disks = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            disks.add(disk("C:", 536870912000L, 268435456000L, 50, false));
        }
        dh.put("fixedDisks", disks);
        dh.put("fixedDiskCount", 65);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many fixedDisks");

        // Exactly 64 is the boundary — accepted.
        Map<String, Object> atCap = goldenHealthy();
        List<Map<String, Object>> capDisks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            capDisks.add(disk("C:", 536870912000L, 268435456000L, 50, false));
        }
        atCap.put("fixedDisks", capDisks);
        atCap.put("fixedDiskCount", 64);
        atCap.put("fixedDisksTruncated", true);
        assertThatCode(() -> policy.sanitize(wrap(atCap))).doesNotThrowAnyException();
    }

    @Test
    void rejectsStringNumericField() {
        // A required integer field delivered as a String (e.g.
        // fixedDiskCount:"1") must NOT be silently coerced — fail-closed.
        Map<String, Object> dh = goldenHealthy();
        dh.put("fixedDiskCount", "1");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .as("string-typed integer field must be rejected")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected integer");

        // Also covers a nested byte total + a percent delivered as String.
        Map<String, Object> dh2 = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> mem2 = (Map<String, Object>) dh2.get("memory");
        mem2.put("usedPercent", "42");
        assertThatThrownBy(() -> policy.sanitize(wrap(dh2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected integer");

        Map<String, Object> dh3 = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks3 = (List<Map<String, Object>>) dh3.get("fixedDisks");
        disks3.get(0).put("totalBytes", "536870912000");
        assertThatThrownBy(() -> policy.sanitize(wrap(dh3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected integer");
    }

    @Test
    void rejectsDecimalIntegerField() {
        // A non-integral decimal (e.g. uptimeDays:1.5) for an integer field
        // must be fail-closed rejected, not floored/coerced.
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) dh.get("uptime");
        up.put("uptimeDays", 1.5d);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .as("fractional double for an integer field must be rejected")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-integral decimal");

        // A BigDecimal with a fractional part is equally rejected.
        Map<String, Object> dh2 = goldenHealthy();
        dh2.put("probeDurationMs", new java.math.BigDecimal("12.7"));
        assertThatThrownBy(() -> policy.sanitize(wrap(dh2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-integral");

        // An integral double (1.0) is the harmless boundary — accepted
        // (Jackson can hand an integer back as a double in some configs).
        Map<String, Object> dh3 = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> up3 = (Map<String, Object>) dh3.get("uptime");
        up3.put("uptimeDays", 3.0d);
        assertThatCode(() -> policy.sanitize(wrap(dh3))).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonStringProbeErrorSummary() {
        // Contract: probeError.summary is type:string. A non-string (here a
        // number) must be fail-closed rejected, not String.valueOf-coerced
        // into bogus operator text.
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("summary", 12345);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected string at");
    }

    // ------------------------------------------------------------------
    // Required v1 fields — fail-closed (Codex P1-2)
    // ------------------------------------------------------------------

    @Test
    void rejectsMinimalSchemaVersionOnlyBlock() {
        // A block carrying ONLY {"schemaVersion":1} must be fail-closed
        // rejected — NOT projected into a healthy-looking default snapshot.
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required device-health field");
    }

    @Test
    void rejectsMissingRequiredTopLevelField() {
        // Each required top-level field, when removed, fails closed.
        for (String required : new String[]{
                "supported", "probeComplete", "fixedDisks", "fixedDiskCount",
                "fixedDisksTruncated", "maxFixedDisks", "memory", "uptime",
                "anyLowDisk", "sourceUsed", "probeDurationMs"}) {
            Map<String, Object> dh = goldenHealthy();
            dh.remove(required);

            assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                    .as("missing required field '%s' must fail closed", required)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing required device-health field");
        }
    }

    @Test
    void rejectsMissingRequiredMemorySubfield() {
        for (String required : new String[]{
                "totalPhysicalBytes", "availableBytes", "usedPercent",
                "highPressureWarning", "commitLimitBytes", "commitUsedBytes"}) {
            Map<String, Object> dh = goldenHealthy();
            @SuppressWarnings("unchecked")
            Map<String, Object> mem = (Map<String, Object>) dh.get("memory");
            mem.remove(required);

            assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                    .as("missing required memory subfield '%s' must fail closed", required)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing required device-health field");
        }
    }

    @Test
    void rejectsMissingRequiredUptimeSubfield() {
        for (String required : new String[]{
                "lastBootEpochSec", "uptimeSeconds", "uptimeDays", "longUptimeWarning"}) {
            Map<String, Object> dh = goldenHealthy();
            @SuppressWarnings("unchecked")
            Map<String, Object> up = (Map<String, Object>) dh.get("uptime");
            up.remove(required);

            assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                    .as("missing required uptime subfield '%s' must fail closed", required)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing required device-health field");
        }
    }

    @Test
    void rejectsWrongTypedRequiredBooleanField() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("supported", "yes"); // not a boolean

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected boolean");
    }

    @Test
    void rejectsMemoryNotObject() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("memory", "not-an-object");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected object");
    }

    // ------------------------------------------------------------------
    // probeErrors[] shape enforcement (Codex P1-1/P1-3)
    // ------------------------------------------------------------------

    @Test
    void rejectsProbeErrorMissingCode() {
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).remove("code");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required probeError code");
    }

    @Test
    void rejectsProbeErrorUnknownCodeEnum() {
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("code", "MADE_UP_CODE");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported probeError code");
    }

    @Test
    void rejectsProbeErrorUnknownSourceEnum() {
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("source", "wmi");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported probeError source");
    }

    @Test
    void rejectsProbeErrorsNotArray() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("probeErrors", "not-an-array");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected array");
    }

    @Test
    void boundsProbeErrorSummaryToContractMaxInPolicyStage() {
        // Codex P1-3: the summary cap (=200, contract maxLength) is enforced
        // in the POLICY stage, so the command-result payload (which carries
        // the policy projection) is bounded — not just the persisted entity
        // scalar. SUMMARY_MAX_LEN is the single shared constant.
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("summary", "x".repeat(500));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) dhOf(policy.sanitize(wrap(dh))).get("probeErrors");
        String summary = (String) out.get(0).get("summary");
        assertThat(summary).hasSize(DeviceHealthPayloadPolicy.SUMMARY_MAX_LEN);
        assertThat(DeviceHealthPayloadPolicy.SUMMARY_MAX_LEN).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Secret reject (key + value pattern)
    // ------------------------------------------------------------------

    @Test
    void rejectsTokenKey() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("token", "anything");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void rejectsBearerInProbeErrorSummary() {
        // The contract says probeError.summary must never be a raw errno
        // or path; defense-in-depth also fail-closed rejects a leaked
        // bearer token in that operator-facing text.
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("summary", "auth header rejected: Bearer abc123def456ghi789jkl");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    @Test
    void rejectsCompactJwtInValuePosition() {
        Map<String, Object> dh = goldenHealthy();
        dh.put("note", "received eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abcdefghi");

        assertThatThrownBy(() -> policy.sanitize(wrap(dh)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern");
    }

    @Test
    void stripsUserPathInProbeErrorSummary() {
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("summary", "probe failed near C:\\Users\\alice\\AppData");

        Map<String, Object> sanitized = policy.sanitize(wrap(dh));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outErrors =
                (List<Map<String, Object>>) dhOf(sanitized).get("probeErrors");
        assertThat(outErrors.get(0).get("summary")).asString()
                .contains(DeviceHealthPayloadPolicy.REDACTED)
                .doesNotContain("alice");
    }

    // ------------------------------------------------------------------
    // Null safety + placement
    // ------------------------------------------------------------------

    @Test
    void nullDetailsReturnsNull() {
        assertThatCode(() -> policy.sanitize(null)).doesNotThrowAnyException();
        assertThat(policy.sanitize(null)).isNull();
    }

    @Test
    void detailsWithoutDeviceHealthSubtreeReturnsCopy() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("software", Map.of("appCount", 0, "apps", List.of())));

        Map<String, Object> sanitized = policy.sanitize(details);

        assertThat(sanitized).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> inv = (Map<String, Object>) sanitized.get("inventory");
        assertThat(inv).doesNotContainKey("deviceHealth");
    }

    @Test
    void topLevelDeviceHealthAliasAlsoValidated() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disks = (List<Map<String, Object>>) dh.get("fixedDisks");
        disks.get(0).put("serialNumber", "SN-TOPLEVEL");
        details.put("deviceHealth", dh);

        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden device-health disk key");
    }

    @Test
    void dropsUnknownTopLevelField() {
        // Forward-compat (contract §"Forward-compat rule"): an unknown
        // top-level field on the device-health block (a newer agent) is
        // IGNORED — not persisted. The allowlist projection drops it (it
        // does NOT survive into the command-result payload nor the snapshot
        // redacted_payload). The known v1 fields still validate + survive.
        Map<String, Object> dh = goldenHealthy();
        dh.put("futureField", "some-value");

        Map<String, Object> sanitized = policy.sanitize(wrap(dh));
        Map<String, Object> out = dhOf(sanitized);
        assertThat(out).doesNotContainKey("futureField");
        // Known v1 fields survive.
        assertThat(out.get("sourceUsed")).isEqualTo("win32");
        assertThat(out.get("supported")).isEqualTo(true);
    }

    @Test
    void dropsOffContractTopLevelFieldsFromLeakSet() {
        // The specific off-contract fields Codex flagged as leaking into
        // the command-result payload + snapshot must be DROPPED (not
        // preserved verbatim by a walk-and-copy).
        Map<String, Object> dh = goldenHealthy();
        dh.put("volumeLabel", "OS");                 // off-contract top-level
        dh.put("hostname", "PC-LEAK");               // off-contract
        dh.put("localBootTime", "2026-05-29 10:00"); // tz/locale leak shape

        Map<String, Object> out = dhOf(policy.sanitize(wrap(dh)));
        assertThat(out).doesNotContainKeys("volumeLabel", "hostname", "localBootTime");
        // The canonical projection carries exactly the v1 keys (no extras).
        assertThat(out.keySet()).containsExactlyInAnyOrder(
                "schemaVersion", "supported", "probeComplete", "fixedDisks",
                "fixedDiskCount", "fixedDisksTruncated", "maxFixedDisks", "memory",
                "uptime", "anyLowDisk", "sourceUsed", "probeDurationMs");
    }

    @Test
    void dropsUnknownMemorySubfield() {
        // memory.processList is off-contract — DROPPED, not persisted.
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> mem = (Map<String, Object>) dh.get("memory");
        mem.put("processList", List.of("explorer.exe", "chrome.exe"));

        @SuppressWarnings("unchecked")
        Map<String, Object> outMem = (Map<String, Object>) dhOf(policy.sanitize(wrap(dh))).get("memory");
        assertThat(outMem).doesNotContainKey("processList");
        assertThat(outMem.keySet()).containsExactlyInAnyOrder(
                "totalPhysicalBytes", "availableBytes", "usedPercent",
                "highPressureWarning", "commitLimitBytes", "commitUsedBytes");
    }

    @Test
    void dropsUnknownUptimeSubfield() {
        // uptime.localBootTime is off-contract (tz/locale leak) — DROPPED.
        Map<String, Object> dh = goldenHealthy();
        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) dh.get("uptime");
        up.put("localBootTime", "Thu May 29 10:00:00 EEST 2026");

        @SuppressWarnings("unchecked")
        Map<String, Object> outUp = (Map<String, Object>) dhOf(policy.sanitize(wrap(dh))).get("uptime");
        assertThat(outUp).doesNotContainKey("localBootTime");
        assertThat(outUp.keySet()).containsExactlyInAnyOrder(
                "lastBootEpochSec", "uptimeSeconds", "uptimeDays", "longUptimeWarning");
    }

    @Test
    void dropsUnknownProbeErrorField() {
        // probeErrors[].rawOutput is off-contract — DROPPED. Only
        // {source, code, summary} survive.
        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("rawOutput", "stderr: GetLogicalDrives failed errno=5");
        errors.get(0).put("stackTrace", "at device_health.go:42");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) dhOf(policy.sanitize(wrap(dh))).get("probeErrors");
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsOnlyKeys("source", "code", "summary");
        assertThat(out.get(0)).doesNotContainKeys("rawOutput", "stackTrace");
    }

    // ------------------------------------------------------------------
    // Fixtures — the three contract golden examples
    // ------------------------------------------------------------------

    private Map<String, Object> goldenHealthy() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        List<Map<String, Object>> disks = new ArrayList<>();
        disks.add(disk("C:", 536870912000L, 268435456000L, 50, false));
        dh.put("fixedDisks", disks);
        dh.put("fixedDiskCount", 1);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(17179869184L, 9663676416L, 42, false, 25769803776L, 10307921920L));
        dh.put("uptime", uptime(1748275200L, 259200L, 3, false));
        dh.put("anyLowDisk", false);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 12);
        return dh;
    }

    private Map<String, Object> goldenWarning() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        List<Map<String, Object>> disks = new ArrayList<>();
        disks.add(disk("C:", 536870912000L, 5368709120L, 1, true));
        dh.put("fixedDisks", disks);
        dh.put("fixedDiskCount", 1);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(17179869184L, 1073741824L, 95, true, 34359738368L, 25769803776L));
        dh.put("uptime", uptime(1745683200L, 2851200L, 33, true));
        dh.put("anyLowDisk", true);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 18);
        return dh;
    }

    private Map<String, Object> goldenUnsupported() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", false);
        dh.put("probeComplete", false);
        dh.put("fixedDisks", new ArrayList<>());
        dh.put("fixedDiskCount", 0);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(0L, 0L, 0, false, 0L, 0L));
        dh.put("uptime", uptime(0L, 0L, 0, false));
        dh.put("anyLowDisk", false);
        dh.put("sourceUsed", "none");
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("source", "none");
        err.put("code", "UNSUPPORTED_PLATFORM");
        err.put("summary", "device-health probe not supported on this runtime");
        errors.add(err);
        dh.put("probeErrors", errors);
        dh.put("probeDurationMs", 0);
        return dh;
    }

    private static Map<String, Object> disk(String letter, long total, long free,
                                            int freePct, boolean low) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("driveLetter", letter);
        d.put("totalBytes", total);
        d.put("freeBytes", free);
        d.put("freePercent", freePct);
        d.put("lowDiskWarning", low);
        return d;
    }

    private static Map<String, Object> memory(long total, long avail, int usedPct,
                                              boolean pressure, long commitLimit, long commitUsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalPhysicalBytes", total);
        m.put("availableBytes", avail);
        m.put("usedPercent", usedPct);
        m.put("highPressureWarning", pressure);
        m.put("commitLimitBytes", commitLimit);
        m.put("commitUsedBytes", commitUsed);
        return m;
    }

    private static Map<String, Object> uptime(long lastBoot, long seconds, int days, boolean longUp) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("lastBootEpochSec", lastBoot);
        u.put("uptimeSeconds", seconds);
        u.put("uptimeDays", days);
        u.put("longUptimeWarning", longUp);
        return u;
    }

    private Map<String, Object> wrap(Map<String, Object> deviceHealth) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("deviceHealth", deviceHealth);
        details.put("inventory", inventory);
        return details;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dhOf(Map<String, Object> sanitized) {
        return (Map<String, Object>) ((Map<String, Object>) sanitized.get("inventory"))
                .get("deviceHealth");
    }
}
